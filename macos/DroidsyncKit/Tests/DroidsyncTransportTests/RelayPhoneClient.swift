import Foundation
import NIOCore
import NIOPosix
import NIOSSL
import NIOTLS
import DroidsyncProtocol
@testable import DroidsyncTransport

enum RelayPhoneClientError: Error {
    case wait
    case relayError(String)
    case unavailable
}

/// NIO phone client: outer TLS to the relay, the join line inside it, then the inner TLS
/// and the Droidsync session protocol once the relay has sent `PAIRED`. A direct client
/// (`room` nil) skips the relay and starts the inner TLS at once, like the local path.
final class RelayPhoneClient {
    var onReady: (() -> Void)?
    var onEnvelope: ((DSEnvelope) -> Void)?
    var onClosed: (() -> Void)?
    var onFailure: ((Error) -> Void)?

    private let joinLine: String?
    private let tlsConfiguration: TLSConfiguration
    private let group: MultiThreadedEventLoopGroup
    private var channel: Channel?

    init(room: String?, identity: TLSIdentity) throws {
        self.joinLine = room.map { RelayJoinLine(room: $0, role: .phone).formatted() }
        var configuration = TLSConfiguration.makeClientConfiguration()
        configuration.certificateVerification = .noHostnameVerification
        let certificate = try NIOSSLCertificate(bytes: Array(identity.certificateDER), format: .der)
        configuration.trustRoots = .certificates([certificate])
        self.tlsConfiguration = configuration
        self.group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    }

    func start(host: String, port: Int) throws {
        let sslContext = try NIOSSLContext(configuration: tlsConfiguration)
        let outerContext = try InProcessRelay.phoneOuterTLSContext()
        let bootstrap = ClientBootstrap(group: group)
            .connectTimeout(.seconds(5))
            .channelInitializer { [weak self] channel in
                guard let self else {
                    return channel.eventLoop.makeFailedFuture(RelayPhoneClientError.unavailable)
                }
                guard let joinLine = self.joinLine else {
                    return channel.eventLoop.makeCompletedFuture(withResultOf: {
                        try channel.pipeline.syncOperations.addHandler(
                            NIOSSLClientHandler(context: sslContext, serverHostname: nil)
                        )
                        try channel.pipeline.syncOperations.addHandler(ByteToMessageHandler(PhoneFrameDecoderHandler()))
                        try channel.pipeline.syncOperations.addHandler(PhoneSessionHandler(
                            onReady: { [weak self] in self?.onReady?() },
                            onEnvelope: { [weak self] envelope in self?.onEnvelope?(envelope) },
                            onClosed: { [weak self] in self?.onClosed?() }
                        ))
                    })
                }
                let bootstrapHandler = RelayPhoneBootstrapHandler(
                    joinLine: joinLine,
                    sslContext: sslContext,
                    onReady: { [weak self] in self?.onReady?() },
                    onEnvelope: { [weak self] envelope in self?.onEnvelope?(envelope) },
                    onClosed: { [weak self] in self?.onClosed?() },
                    onFailure: { [weak self] error in self?.onFailure?(error) }
                )
                return channel.eventLoop.makeCompletedFuture(withResultOf: {
                    // Outer TLS to the relay first; the inner TLS to the Mac is added above
                    // the bootstrap handler once the relay sends PAIRED.
                    try channel.pipeline.syncOperations.addHandler(
                        InProcessRelay.phoneOuterTLSHandler(context: outerContext)
                    )
                    try channel.pipeline.syncOperations.addHandler(bootstrapHandler)
                })
            }
        bootstrap.connect(host: host, port: port).whenComplete { [weak self] result in
            switch result {
            case .success(let channel):
                self?.channel = channel
            case .failure(let error):
                self?.onFailure?(error)
            }
        }
    }

    func send(_ envelope: DSEnvelope) {
        guard let channel, let data = try? envelope.serializedData() else { return }
        var buffer = channel.allocator.buffer(capacity: data.count + 4)
        buffer.writeBytes(FrameCodec.encode(data))
        channel.writeAndFlush(buffer, promise: nil)
    }

    func cancel() {
        channel?.close(promise: nil)
    }

    func shutdown() {
        channel?.close(promise: nil)
        channel = nil
        try? group.syncShutdownGracefully()
    }
}

private final class RelayPhoneBootstrapHandler: ChannelInboundHandler, RemovableChannelHandler {
    typealias InboundIn = ByteBuffer

    private let joinLine: String
    private let sslContext: NIOSSLContext
    private let onReady: () -> Void
    private let onEnvelope: (DSEnvelope) -> Void
    private let onClosed: () -> Void
    private let onFailure: (Error) -> Void
    private var buffer = ByteBufferAllocator().buffer(capacity: 0)
    private var paired = false

    init(
        joinLine: String,
        sslContext: NIOSSLContext,
        onReady: @escaping () -> Void,
        onEnvelope: @escaping (DSEnvelope) -> Void,
        onClosed: @escaping () -> Void,
        onFailure: @escaping (Error) -> Void
    ) {
        self.joinLine = joinLine
        self.sslContext = sslContext
        self.onReady = onReady
        self.onEnvelope = onEnvelope
        self.onClosed = onClosed
        self.onFailure = onFailure
    }

    func channelActive(context: ChannelHandlerContext) {
        var out = context.channel.allocator.buffer(capacity: joinLine.utf8.count)
        out.writeString(joinLine)
        context.channel.writeAndFlush(out, promise: nil)
        context.fireChannelActive()
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var incoming = unwrapInboundIn(data)
        buffer.writeBuffer(&incoming)
        guard !paired else { return }
        guard let newline = buffer.readableBytesView.firstIndex(of: 0x0A) else { return }
        let length = newline - buffer.readableBytesView.startIndex + 1
        guard let lineBytes = buffer.readBytes(length: length) else { return }
        let line = String(decoding: lineBytes.dropLast(), as: UTF8.self)
        do {
            switch try RelayResponse.parse(line) {
            case .paired:
                paired = true
                try installPipeline(context: context)
            case .wait:
                throw RelayPhoneClientError.wait
            case .error(let reason):
                throw RelayPhoneClientError.relayError(reason)
            }
        } catch {
            onFailure(error)
            context.close(promise: nil)
        }
    }

    private func installPipeline(context: ChannelHandlerContext) throws {
        let clientHandler = try NIOSSLClientHandler(context: sslContext, serverHostname: nil)
        try context.pipeline.syncOperations.addHandler(clientHandler, name: "phone.tls")
        try context.pipeline.syncOperations.addHandler(
            ByteToMessageHandler(PhoneFrameDecoderHandler()),
            name: "phone.frames"
        )
        try context.pipeline.syncOperations.addHandler(
            PhoneSessionHandler(onReady: onReady, onEnvelope: onEnvelope, onClosed: onClosed),
            name: "phone.session"
        )
        let buffered = buffer
        buffer.clear()
        if buffered.readableBytes > 0 {
            context.fireChannelRead(NIOAny(buffered))
        }
        context.fireChannelReadComplete()
        context.pipeline.removeHandler(self, promise: nil)
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        onFailure(error)
        context.close(promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        onClosed()
        context.fireChannelInactive()
    }
}

private struct PhoneFrameDecoderHandler: ByteToMessageDecoder {
    typealias InboundOut = DSEnvelope

    private var decoder = FrameDecoder()

    mutating func decode(context: ChannelHandlerContext, buffer: inout ByteBuffer) throws -> DecodingState {
        guard buffer.readableBytes > 0 else { return .needMoreData }
        let data = Data(buffer.readableBytesView)
        buffer.moveReaderIndex(forwardBy: data.count)
        for frame in try decoder.append(data) {
            let envelope = try DSEnvelope(serializedBytes: frame)
            context.fireChannelRead(wrapInboundOut(envelope))
        }
        return .needMoreData
    }

    mutating func decodeLast(
        context: ChannelHandlerContext,
        buffer: inout ByteBuffer,
        seenEOF: Bool
    ) throws -> DecodingState {
        try decode(context: context, buffer: &buffer)
    }
}

private final class PhoneSessionHandler: ChannelInboundHandler {
    typealias InboundIn = DSEnvelope

    private let onReady: () -> Void
    private let onEnvelope: (DSEnvelope) -> Void
    private let onClosed: () -> Void

    init(
        onReady: @escaping () -> Void,
        onEnvelope: @escaping (DSEnvelope) -> Void,
        onClosed: @escaping () -> Void
    ) {
        self.onReady = onReady
        self.onEnvelope = onEnvelope
        self.onClosed = onClosed
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let tlsEvent = event as? TLSUserEvent, case .handshakeCompleted = tlsEvent {
            onReady()
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        onEnvelope(unwrapInboundIn(data))
    }

    func channelInactive(context: ChannelHandlerContext) {
        onClosed()
        context.fireChannelInactive()
    }
}
