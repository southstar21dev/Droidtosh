import Foundation
import Network
import DroidsyncProtocol

final class ServerSession {
    static let supportedProtocolVersion: UInt32 = ProtocolV2.version

    var onEnvelope: ((DSEnvelope) -> Void)?
    var onAuthenticated: ((Data) -> Void)?
    var onClosed: (() -> Void)?

    private let connection: NWConnection
    private var handshake: ServerHandshake
    private var decoder = FrameDecoder()
    private let queue: DispatchQueue
    private var lastActivity = Date()
    private var timeoutTimer: DispatchSourceTimer?
    private var closed = false

    init(connection: NWConnection, psks: [Data], queue: DispatchQueue) {
        self.connection = connection
        self.handshake = ServerHandshake(psks: psks)
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.send(self.handshake.start())
                self.startTimeoutTimer()
                self.receive()
            case .failed, .cancelled:
                self.close()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func send(_ envelope: DSEnvelope) {
        send(envelope, thenClose: false)
    }

    func close() {
        guard !closed else { return }
        closed = true
        timeoutTimer?.cancel()
        timeoutTimer = nil
        connection.cancel()
        onClosed?()
    }

    private func send(_ envelope: DSEnvelope, thenClose: Bool) {
        guard let data = try? envelope.serializedData() else {
            if thenClose { close() }
            return
        }
        connection.send(content: FrameCodec.encode(data), completion: .contentProcessed { [weak self] _ in
            guard let self, thenClose else { return }
            self.close()
        })
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            self.lastActivity = Date()
            do {
                if let data, !data.isEmpty {
                    for frame in try self.decoder.append(data) {
                        guard try self.handle(frame) else { return }
                    }
                }
            } catch {
                self.close()
                return
            }
            if isComplete || error != nil {
                self.close()
                return
            }
            self.receive()
        }
    }

    private func handle(_ frame: Data) throws -> Bool {
        let envelope = try DSEnvelope(serializedBytes: frame)
        guard envelope.protocolVersion == Self.supportedProtocolVersion else {
            var protocolError = DSProtocolError()
            protocolError.code = "unsupported_protocol_version"
            protocolError.message = "Unsupported protocol version \(envelope.protocolVersion)"
            send(makeEnvelope(.error(protocolError)), thenClose: true)
            return false
        }
        switch handshake.state {
        case .awaitingResponse:
            let response = try handshake.handle(envelope)
            if case .authenticated(let psk) = handshake.state {
                onAuthenticated?(psk)
            }
            if let response { send(response) }
        case .authenticated:
            if case .ping(let ping)? = envelope.payload {
                var pong = DSPong()
                pong.sentAtMs = ping.sentAtMs
                send(makeEnvelope(.pong(pong)))
            }
            onEnvelope?(envelope)
        case .failed:
            throw AuthError.invalidMessage
        }
        return true
    }

    private func startTimeoutTimer() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if Date().timeIntervalSince(self.lastActivity) > 60 {
                self.close()
            }
        }
        timer.resume()
        timeoutTimer = timer
    }
}
