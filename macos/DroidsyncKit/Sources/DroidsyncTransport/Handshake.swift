import Foundation
import Security
import DroidsyncProtocol

/// The outcome of the v2 version and capability negotiation (PROTOCOL-v2.md §3).
public struct Negotiation: Equatable {
    public let version: UInt32
    /// The capabilities both sides support, in the Mac's order.
    public let capabilities: [String]

    public init(version: UInt32, capabilities: [String]) {
        self.version = version
        self.capabilities = capabilities
    }

    public func supports(_ capability: String) -> Bool {
        capabilities.contains(capability)
    }

    /// Picks the highest version both ranges contain, or nil when they don't overlap.
    /// A zero `max` means the peer sent no range, which never matches.
    static func version(
        localMin: UInt32, localMax: UInt32,
        peerMin: UInt32, peerMax: UInt32
    ) -> UInt32? {
        guard peerMax > 0 else { return nil }
        let version = min(localMax, peerMax)
        return version >= max(localMin, peerMin) ? version : nil
    }
}

public struct ServerHandshake {
    public enum State: Equatable {
        case awaitingResponse(serverNonce: Data)
        case authenticated(psk: Data)
        case failed
    }

    private let psks: [Data]
    private let capabilities: [String]
    private let minVersion: UInt32
    private let maxVersion: UInt32
    private let nonceGenerator: () -> Data
    public private(set) var state: State = .failed
    public private(set) var negotiation: Negotiation?

    public init(
        psks: [Data],
        capabilities: [String] = ProtocolV2.macCapabilities,
        minVersion: UInt32 = ProtocolV2.minVersion,
        maxVersion: UInt32 = ProtocolV2.maxVersion,
        nonceGenerator: @escaping () -> Data = { randomNonce() }
    ) {
        self.psks = psks
        self.capabilities = capabilities
        self.minVersion = minVersion
        self.maxVersion = maxVersion
        self.nonceGenerator = nonceGenerator
    }

    public mutating func start() -> DSEnvelope {
        let nonce = nonceGenerator()
        state = .awaitingResponse(serverNonce: nonce)
        var challenge = DSAuthChallenge()
        challenge.serverNonce = nonce
        challenge.minVersion = minVersion
        challenge.maxVersion = maxVersion
        challenge.capabilities = capabilities
        return makeEnvelope(.authChallenge(challenge))
    }

    /// Verifies the phone's MAC first, so an unauthenticated peer learns nothing about the
    /// negotiation, then picks the version. Throws `AuthError.unsupportedVersion` when the
    /// ranges don't overlap.
    public mutating func handle(_ envelope: DSEnvelope) throws -> DSEnvelope? {
        guard case .awaitingResponse(let serverNonce) = state,
              case .authResponse(let response)? = envelope.payload,
              response.clientNonce.count == Auth.nonceLength else {
            state = .failed
            throw AuthError.invalidMessage
        }
        for psk in psks {
            let expected = Auth.mac(psk: psk, role: "client", serverNonce: serverNonce, clientNonce: response.clientNonce)
            guard Auth.constantTimeEquals(expected, response.mac) else { continue }
            guard let version = Negotiation.version(
                localMin: minVersion, localMax: maxVersion,
                peerMin: response.minVersion, peerMax: response.maxVersion
            ) else {
                state = .failed
                throw AuthError.unsupportedVersion
            }
            let peerCapabilities = Set(response.capabilities)
            let negotiation = Negotiation(
                version: version,
                capabilities: capabilities.filter { peerCapabilities.contains($0) }
            )
            self.negotiation = negotiation
            state = .authenticated(psk: psk)
            var ok = DSAuthOk()
            ok.mac = Auth.mac(psk: psk, role: "server", serverNonce: serverNonce, clientNonce: response.clientNonce)
            ok.version = negotiation.version
            ok.capabilities = negotiation.capabilities
            return makeEnvelope(.authOk(ok))
        }
        state = .failed
        throw AuthError.invalidMAC
    }
}

public struct ClientHandshake {
    public enum State: Equatable {
        case initial
        case awaitingAuthOk(serverNonce: Data, clientNonce: Data)
        case authenticated
        case failed
    }

    private let psk: Data
    private let capabilities: [String]
    private let minVersion: UInt32
    private let maxVersion: UInt32
    private let nonceGenerator: () -> Data
    public private(set) var state: State = .initial
    public private(set) var negotiation: Negotiation?

    public init(
        psk: Data,
        capabilities: [String] = ProtocolV2.macCapabilities,
        minVersion: UInt32 = ProtocolV2.minVersion,
        maxVersion: UInt32 = ProtocolV2.maxVersion,
        nonceGenerator: @escaping () -> Data = { randomNonce() }
    ) {
        self.psk = psk
        self.capabilities = capabilities
        self.minVersion = minVersion
        self.maxVersion = maxVersion
        self.nonceGenerator = nonceGenerator
    }

    public mutating func handleChallenge(_ envelope: DSEnvelope) throws -> DSEnvelope {
        guard case .initial = state,
              case .authChallenge(let challenge)? = envelope.payload,
              challenge.serverNonce.count == Auth.nonceLength else {
            state = .failed
            throw AuthError.invalidMessage
        }
        let clientNonce = nonceGenerator()
        state = .awaitingAuthOk(serverNonce: challenge.serverNonce, clientNonce: clientNonce)
        var response = DSAuthResponse()
        response.clientNonce = clientNonce
        response.mac = Auth.mac(psk: psk, role: "client", serverNonce: challenge.serverNonce, clientNonce: clientNonce)
        response.minVersion = minVersion
        response.maxVersion = maxVersion
        response.capabilities = capabilities
        return makeEnvelope(.authResponse(response))
    }

    public mutating func handleAuthOk(_ envelope: DSEnvelope) throws {
        guard case .awaitingAuthOk(let serverNonce, let clientNonce) = state,
              case .authOk(let ok)? = envelope.payload else {
            state = .failed
            throw AuthError.invalidMessage
        }
        let expected = Auth.mac(psk: psk, role: "server", serverNonce: serverNonce, clientNonce: clientNonce)
        guard Auth.constantTimeEquals(expected, ok.mac) else {
            state = .failed
            throw AuthError.invalidMAC
        }
        guard ok.version >= minVersion, ok.version <= maxVersion else {
            state = .failed
            throw AuthError.unsupportedVersion
        }
        negotiation = Negotiation(version: ok.version, capabilities: ok.capabilities)
        state = .authenticated
    }
}

public func randomNonce() -> Data {
    var bytes = Data(count: Auth.nonceLength)
    let status = bytes.withUnsafeMutableBytes { buffer in
        SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
    }
    precondition(status == errSecSuccess, "SecRandomCopyBytes failed with status \(status)")
    return bytes
}

func makeEnvelope(_ payload: DSEnvelope.OneOf_Payload) -> DSEnvelope {
    protocolEnvelope(payload)
}
