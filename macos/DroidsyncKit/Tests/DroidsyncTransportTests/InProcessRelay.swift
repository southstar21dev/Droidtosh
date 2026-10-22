import Foundation
import NIOCore
import NIOPosix
import NIOSSL
@testable import DroidsyncTransport

enum InProcessRelayError: Error {
    case bindFailed
}

/// A tiny stand-in for the Go relay: outer TLS with a self-signed certificate, the
/// `DROIDSYNC/2` join line, then a byte pipe between the paired mac and phone.
final class InProcessRelay {
    let port: Int
    /// SHA-256 of the relay certificate's DER bytes, as clients pin it.
    let fingerprint: String

    private let core: RelayCore
    private let group: MultiThreadedEventLoopGroup
    private let serverChannel: Channel
    private var phoneChannels: [Channel] = []

    var connectAttempts: Int { core.connectAttempts }
    var parkedMacCount: Int { core.parkedMacCount }
    var pairedCount: Int { core.pairedCount }

    init() throws {
        let identity = try TLSIdentityGenerator.generate()
        let sslContext = try identity.nioContext()
        let core = RelayCore()
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        let bootstrap = ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 16)
            .childChannelInitializer { channel in
                channel.eventLoop.makeCompletedFuture(withResultOf: {
                    try channel.pipeline.syncOperations.addHandler(NIOSSLServerHandler(context: sslContext))
                    try channel.pipeline.syncOperations.addHandler(RelayJoinHandler(core: core))
                })
            }
        let serverChannel = try bootstrap.bind(host: "127.0.0.1", port: 0).wait()
        guard let port = serverChannel.localAddress?.port else {
            try? group.syncShutdownGracefully()
            throw InProcessRelayError.bindFailed
        }
        self.core = core
        self.group = group
        self.serverChannel = serverChannel
        self.port = port
        self.fingerprint = identity.fingerprint
    }

    func closeParkedMac() {
        core.parkedMac?.close(promise: nil)
    }

    /// Bytes the relay appends to the `PAIRED` write it sends to the mac when the
    /// next phone joins, so tests can exercise the transport's leftover handling.
    func setPairedTrailingBytes(_ data: Data) {
        core.setPairedTrailing(data)
    }

    /// Bytes the relay writes to every mac right after it parks, standing in for a
    /// misbehaving relay.
    func setMacJoinResponse(_ data: Data) {
        core.setMacJoinResponse(data)
    }

    /// Opens a phone connection to the relay with outer TLS up; the caller writes the
    /// join line.
    func connectPhone() throws -> Channel {
        let context = try Self.phoneOuterTLSContext()
        let channel = try ClientBootstrap(group: group)
            .channelInitializer { channel in
                channel.eventLoop.makeCompletedFuture(withResultOf: {
                    try channel.pipeline.syncOperations.addHandler(Self.phoneOuterTLSHandler(context: context))
                })
            }
            .connect(host: "127.0.0.1", port: port)
            .wait()
        phoneChannels.append(channel)
        return channel
    }

    /// A client TLS configuration that accepts any relay certificate; tests that care
    /// about pinning do it on the Mac side.
    static func phoneOuterTLSContext() throws -> NIOSSLContext {
        var configuration = TLSConfiguration.makeClientConfiguration()
        configuration.certificateVerification = .noHostnameVerification
        return try NIOSSLContext(configuration: configuration)
    }

    static func phoneOuterTLSHandler(context: NIOSSLContext) throws -> NIOSSLClientHandler {
        try NIOSSLClientHandler(context: context, serverHostname: nil) { _, promise in
            promise.succeed(.certificateVerified)
        }
    }

    func shutdown() {
        phoneChannels.forEach { $0.close(promise: nil) }
        phoneChannels.removeAll()
        serverChannel.close(promise: nil)
        try? group.syncShutdownGracefully()
    }
}

private final class RelayCore {
    private let lock = NSLock()
    private var attempts = 0
    private var paired = 0
    private var mac: Channel?
    private var pairedTrailing = Data()
    private var macResponse = Data()

    var macJoinResponse: Data { lock.withLock { macResponse } }

    func setMacJoinResponse(_ data: Data) {
        lock.withLock { macResponse = data }
    }

    var connectAttempts: Int { lock.withLock { attempts } }
    var pairedCount: Int { lock.withLock { paired } }
    var parkedMacCount: Int { lock.withLock { mac == nil ? 0 : 1 } }
    var parkedMac: Channel? { lock.withLock { mac } }

    func macJoined(_ channel: Channel) -> Channel? {
        lock.withLock {
            attempts += 1
            let previous = mac
            mac = channel
            return previous
        }
    }

    func takeParkedMac() -> Channel? {
        lock.withLock {
            guard let channel = mac else { return nil }
            mac = nil
            paired += 1
            return channel
        }
    }

    func noteMacClosed(_ channel: Channel) {
        lock.withLock {
            if mac === channel { mac = nil }
        }
    }

    func setPairedTrailing(_ data: Data) {
        lock.withLock { pairedTrailing = data }
    }

    func takePairedTrailing() -> Data {
        lock.withLock {
            let data = pairedTrailing
            pairedTrailing = Data()
            return data
        }
    }
}

private final class RelayJoinHandler: ChannelInboundHandler, RemovableChannelHandler {
    typealias InboundIn = ByteBuffer

    private let core: RelayCore
    private var buffer = ByteBufferAllocator().buffer(capacity: 0)
    private var joined = false

    init(core: RelayCore) {
        self.core = core
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        guard !joined else { return }
        var incoming = unwrapInboundIn(data)
        buffer.writeBuffer(&incoming)
        guard let newline = buffer.readableBytesView.firstIndex(of: 0x0A) else { return }
        let length = newline - buffer.readableBytesView.startIndex + 1
        guard let lineBytes = buffer.readBytes(length: length) else { return }
        joined = true
        context.pipeline.removeHandler(self, promise: nil)
        let line = String(decoding: lineBytes.dropLast(), as: UTF8.self)
        handle(line: line, leftover: buffer, channel: context.channel)
    }

    private func handle(line: String, leftover: ByteBuffer, channel: Channel) {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        if parts.first == "DROIDSYNC/1" {
            let write = channel.eventLoop.makePromise(of: Void.self)
            channel.writeAndFlush(Self.buffer("ERROR upgrade\n", for: channel), promise: write)
            write.futureResult.whenComplete { _ in channel.close(promise: nil) }
            return
        }
        guard parts.count >= 3, parts[0] == "DROIDSYNC/2" else {
            channel.close(promise: nil)
            return
        }
        switch parts[2] {
        case "mac":
            let previous = core.macJoined(channel)
            let core = self.core
            channel.closeFuture.whenComplete { _ in core.noteMacClosed(channel) }
            previous?.close(promise: nil)
            let response = core.macJoinResponse
            if !response.isEmpty {
                var out = channel.allocator.buffer(capacity: response.count)
                out.writeBytes(response)
                channel.writeAndFlush(out, promise: nil)
            }
        case "phone":
            guard let mac = core.takeParkedMac() else {
                let out = Self.buffer("WAIT\n", for: channel)
                let write = channel.eventLoop.makePromise(of: Void.self)
                channel.writeAndFlush(out, promise: write)
                write.futureResult.whenComplete { _ in channel.close(promise: nil) }
                return
            }
            pair(mac: mac, phone: channel, phoneLeftover: leftover)
        default:
            channel.close(promise: nil)
        }
    }

    private func pair(mac: Channel, phone: Channel, phoneLeftover: ByteBuffer) {
        var paired = Self.buffer("PAIRED\n", for: mac)
        let trailing = core.takePairedTrailing()
        if !trailing.isEmpty {
            paired.writeBytes(trailing)
        }
        mac.writeAndFlush(paired, promise: nil)
        write("PAIRED\n", to: phone)
        _ = mac.pipeline.addHandler(RelayPipeHandler(peer: phone), name: "relay.pipe")
        _ = phone.pipeline.addHandler(RelayPipeHandler(peer: mac), name: "relay.pipe")
        if phoneLeftover.readableBytes > 0 {
            mac.writeAndFlush(phoneLeftover, promise: nil)
        }
    }

    private func write(_ text: String, to channel: Channel) {
        channel.writeAndFlush(Self.buffer(text, for: channel), promise: nil)
    }

    private static func buffer(_ text: String, for channel: Channel) -> ByteBuffer {
        var buffer = channel.allocator.buffer(capacity: text.utf8.count)
        buffer.writeString(text)
        return buffer
    }
}

/// Forwards the decrypted outer-TLS bytes (the inner TLS records) to the peer, which
/// re-encrypts them with its own outer TLS.
private final class RelayPipeHandler: ChannelInboundHandler {
    typealias InboundIn = ByteBuffer

    private let peer: Channel

    init(peer: Channel) {
        self.peer = peer
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        peer.writeAndFlush(unwrapInboundIn(data), promise: nil)
    }

    func channelInactive(context: ChannelHandlerContext) {
        peer.close(promise: nil)
        context.fireChannelInactive()
    }
}
