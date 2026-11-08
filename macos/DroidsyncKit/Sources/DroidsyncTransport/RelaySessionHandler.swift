import Foundation
import NIOCore
import NIOSSL
import NIOTLS
import DroidsyncProtocol

/// Streams TLS plaintext through the existing `FrameDecoder` and emits `DSEnvelope`s.
final class RelayFrameDecoderHandler: ByteToMessageDecoder {
    typealias InboundOut = DSEnvelope

    private var decoder = FrameDecoder()
    private let diagnostics: DiagnosticsRecorder?

    init(diagnostics: DiagnosticsRecorder? = nil) {
        self.diagnostics = diagnostics
    }

    func decode(context: ChannelHandlerContext, buffer: inout ByteBuffer) throws -> DecodingState {
        guard buffer.readableBytes > 0 else { return .needMoreData }
        let data = Data(buffer.readableBytesView)
        buffer.moveReaderIndex(forwardBy: data.count)
        let frames: [Data]
        do {
            frames = try decoder.append(data)
        } catch {
            diagnostics?.recordRejectedFrame(.oversize, detail: "frame rejected: \(error)")
            throw error
        }
        for frame in frames {
            let envelope: DSEnvelope
            do {
                envelope = try DSEnvelope(serializedBytes: frame)
            } catch {
                diagnostics?.recordRejectedFrame(.undecodable, detail: "undecodable frame (\(frame.count) bytes)")
                throw error
            }
            diagnostics?.recordInbound(envelope.payload, bytes: frame.count + 4)
            context.fireChannelRead(wrapInboundOut(envelope))
        }
        return .needMoreData
    }

    func decodeLast(
        context: ChannelHandlerContext,
        buffer: inout ByteBuffer,
        seenEOF: Bool
    ) throws -> DecodingState {
        try decode(context: context, buffer: &buffer)
    }
}

/// Port of `ServerSession`'s state machine over NIO: challenge-on-TLS-ready, PSK
/// authentication with version/capability negotiation, protocol-version enforcement,
/// ping/pong and envelope delivery.
final class RelaySessionHandler: ChannelInboundHandler {
    typealias InboundIn = DSEnvelope

    static let idleTimeout: TimeAmount = .seconds(120)

    var onEnvelope: ((DSEnvelope) -> Void)?
    var onAuthenticated: ((Data, Negotiation) -> Void)?
    var onClosed: (() -> Void)?
    var onError: ((String) -> Void)?
    var diagnostics: DiagnosticsRecorder?

    private var handshake: ServerHandshake
    private var started = false
    private var closing = false
    private var closed = false

    init(psks: [Data]) {
        self.handshake = ServerHandshake(psks: psks)
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let tlsEvent = event as? TLSUserEvent, case .handshakeCompleted = tlsEvent {
            startChallenge(context: context)
        } else if let idleEvent = event as? IdleStateHandler.IdleStateEvent {
            switch idleEvent {
            case .read:
                close(context: context)
            case .write, .all:
                break
            }
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        guard !closed, !closing else { return }
        let envelope = unwrapInboundIn(data)
        do {
            try handle(envelope, context: context)
        } catch {
            onError?("session read error: \(error)")
            if let authError = error as? AuthError, authError == .invalidMAC {
                diagnostics?.recordEvent(.authFailure, "phone failed the pairing-key check")
                var protocolError = DSProtocolError()
                protocolError.code = "invalid_psk"
                protocolError.message = "Pairing expired; re-pair with the Mac"
                send(context: context, makeEnvelope(.error(protocolError)), thenClose: true)
                return
            }
            if let authError = error as? AuthError, authError == .unsupportedVersion {
                diagnostics?.recordEvent(.protocolError, "no common protocol version with the phone")
                closing = true
                var protocolError = DSProtocolError()
                protocolError.code = "unsupported_version"
                protocolError.message = "No common protocol version; update the app on both devices"
                send(context: context, makeEnvelope(.error(protocolError)), thenClose: true)
                return
            }
            close(context: context)
        }
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        onError?("session error: \(error)")
        close(context: context)
    }

    func channelInactive(context: ChannelHandlerContext) {
        finish()
        context.fireChannelInactive()
    }

    private func startChallenge(context: ChannelHandlerContext) {
        guard !started, !closed, !closing else { return }
        started = true
        send(context: context, handshake.start())
    }

    /// Before authentication only v2 frames are parsed; afterwards every frame must carry
    /// the negotiated version.
    private var expectedProtocolVersion: UInt32 {
        handshake.negotiation?.version ?? ProtocolV2.version
    }

    private func handle(_ envelope: DSEnvelope, context: ChannelHandlerContext) throws {
        guard envelope.protocolVersion == expectedProtocolVersion else {
            // Stop reading immediately; the close only lands once the error write
            // completes, and coalesced frames behind this one must not be processed
            // (matches `ServerSession.handle` returning false).
            closing = true
            diagnostics?.recordRejectedFrame(
                .wrongVersion,
                detail: "envelope version \(envelope.protocolVersion), expected \(expectedProtocolVersion)"
            )
            var protocolError = DSProtocolError()
            protocolError.code = "unsupported_protocol_version"
            protocolError.message = "Unsupported protocol version \(envelope.protocolVersion)"
            send(context: context, makeEnvelope(.error(protocolError)), thenClose: true)
            return
        }
        switch handshake.state {
        case .awaitingResponse:
            let response = try handshake.handle(envelope)
            // AuthOk goes out before the transport hears about the session, so the phone
            // always sees it ahead of the Mac's Resume.
            if let response { send(context: context, response) }
            if case .authenticated(let psk) = handshake.state, let negotiation = handshake.negotiation {
                onAuthenticated?(psk, negotiation)
            }
        case .authenticated:
            if case .ping(let ping)? = envelope.payload {
                var pong = DSPong()
                pong.sentAtMs = ping.sentAtMs
                send(context: context, makeEnvelope(.pong(pong)))
            }
            onEnvelope?(envelope)
        case .failed:
            throw AuthError.invalidMessage
        }
    }

    private func send(context: ChannelHandlerContext, _ envelope: DSEnvelope, thenClose: Bool = false) {
        guard let data = try? envelope.serializedData() else {
            if thenClose { close(context: context) }
            return
        }
        var buffer = context.channel.allocator.buffer(capacity: data.count + 4)
        buffer.writeBytes(FrameCodec.encode(data))
        diagnostics?.recordOutbound(envelope.payload, bytes: data.count + 4)
        if thenClose {
            let promise = context.eventLoop.makePromise(of: Void.self)
            context.writeAndFlush(NIOAny(buffer), promise: promise)
            promise.futureResult.whenComplete { [weak self] _ in
                self?.close(context: context)
            }
        } else {
            context.writeAndFlush(NIOAny(buffer), promise: nil)
        }
    }

    private func close(context: ChannelHandlerContext) {
        guard !closed else { return }
        closing = true
        closed = true
        context.close(promise: nil)
        onClosed?()
    }

    private func finish() {
        guard !closed else { return }
        closing = true
        closed = true
        onClosed?()
    }
}
