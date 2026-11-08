import Foundation
import Network
import Security
@testable import DroidsyncProtocol
@testable import DroidsyncTransport

final class LoopbackClient {
    let connection: NWConnection
    var onReady: (() -> Void)?
    var onEnvelope: ((DSEnvelope) -> Void)?
    var onClosed: (() -> Void)?

    private var decoder = FrameDecoder()
    private let queue = DispatchQueue(label: "loopback.client")

    init(port: UInt16, expectedFingerprint: String) {
        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] ?? []
            guard let leaf = chain.first else { complete(false); return }
            let fingerprint = Fingerprint.hexSHA256(SecCertificateCopyData(leaf) as Data)
            complete(fingerprint == expectedFingerprint)
        }, .main)
        let parameters = NWParameters(tls: tlsOptions, tcp: .init())
        connection = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: parameters)
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.onReady?() }
        }
        connection.start(queue: queue)
    }

    func receive() {
        pump()
    }

    func send(_ envelope: DSEnvelope) {
        guard let data = try? envelope.serializedData() else { return }
        connection.send(content: FrameCodec.encode(data), completion: .contentProcessed { _ in })
    }

    func cancel() {
        connection.cancel()
    }

    private func pump() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, let frames = try? self.decoder.append(data) {
                for frame in frames {
                    guard let envelope = try? DSEnvelope(serializedBytes: frame) else { continue }
                    self.onEnvelope?(envelope)
                }
            }
            if isComplete || error != nil {
                self.onClosed?()
            } else {
                self.pump()
            }
        }
    }
}
