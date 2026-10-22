import Foundation
import DroidsyncKit

/// Owns the pairing secrets (paired and pending PSK), the relay room token and the pairing QR URI.
@MainActor
final class PairingController: ObservableObject {
    nonisolated private static let pskName = "paired-psk"
    nonisolated private static let roomTokenName = "room-token"

    @Published private(set) var pairingURI: String?
    @Published private(set) var roomToken: String = PairingController.loadOrCreateRoomToken()
    /// Whether a phone has paired (a paired PSK is stored).
    @Published private(set) var isPaired = false

    /// The relay endpoint the pairing URI advertises; nil while the relay settings are incomplete.
    var endpointProvider: () -> RelayEndpoint? = { nil }
    /// The pinned relay certificate fingerprint; nil until it is known.
    var relayFingerprintProvider: () -> String? = { nil }
    /// The Mac's connection mode, which the code hands to the phone.
    var modeProvider: () -> ConnectionMode = { .relay }
    /// The local listener's port; nil while it is invalid.
    var localPortProvider: () -> UInt16? = { nil }
    /// Reports trouble reading the secrets for the status line.
    var onStatus: ((String) -> Void)?
    /// Fires once a room token is finally read after it was unreadable at launch.
    var onRoomTokenRecovered: (() -> Void)?

    // Read on the relay's event loop through `pskCandidates()`; every access holds `pskLock`.
    nonisolated(unsafe) private var pairedPSK: Data?
    nonisolated(unsafe) private var pendingPSK = Data()
    nonisolated private let pskLock = NSLock()
    private var fingerprint: String?

    /// This Mac's certificate fingerprint, once the identity is loaded.
    var macFingerprint: String? { fingerprint }

    /// Strings that must never appear in anything the user copies or exports: the room
    /// token, the PSKs in every encoding they could be logged in, and the pairing URI.
    var redactionSecrets: [String] {
        var secrets = [roomToken]
        for psk in pskCandidates() where !psk.isEmpty {
            secrets.append(Base64URL.encode(psk))
            secrets.append(psk.base64EncodedString())
            secrets.append(psk.map { String(format: "%02x", $0) }.joined())
        }
        if let pairingURI { secrets.append(pairingURI) }
        return secrets.filter { !$0.isEmpty }
    }

    func loadSecrets(fingerprint: String) {
        pskLock.lock()
        pendingPSK = randomNonce()
        do {
            pairedPSK = try SecretStore.default.load(Self.pskName)
            DebugLog.log("relay: pairedPSK loaded=\(pairedPSK != nil)")
        } catch {
            pairedPSK = nil
            DebugLog.log("relay: pairedPSK load failed: \(error)")
        }
        isPaired = pairedPSK != nil
        pskLock.unlock()
        self.fingerprint = fingerprint
    }

    /// PSKs the handshake accepts: the pending one from the QR code, then the paired one.
    nonisolated func pskCandidates() -> [Data] {
        pskLock.lock()
        defer { pskLock.unlock() }
        return [pendingPSK] + (pairedPSK.map { [$0] } ?? [])
    }

    /// Records the PSK the phone authenticated with. A new PSK means a fresh pairing: it is kept
    /// and the QR code rotates to a new pending PSK.
    func handleAuthenticated(psk: Data) {
        pskLock.lock()
        let pskMatched = pairedPSK != nil && psk == pairedPSK
        if !pskMatched {
            pairedPSK = psk
            try? SecretStore.default.save(psk, as: Self.pskName)
            pendingPSK = randomNonce()
        }
        pskLock.unlock()
        isPaired = true
        DebugLog.log("relay: authenticated pskMatched=\(pskMatched)")
        if !pskMatched {
            refreshPairingURI()
        }
    }

    /// Forgets the paired phone and moves to a new room so it can no longer reach this Mac.
    func forgetPairing() {
        pskLock.lock()
        pairedPSK = nil
        pendingPSK = randomNonce()
        pskLock.unlock()
        isPaired = false
        SecretStore.default.delete(Self.pskName)
        let token = RoomToken.generate()
        try? SecretStore.default.save(Data(token.utf8), as: Self.roomTokenName)
        roomToken = token
    }

    /// No QR code is offered until everything the mode needs is known. With the relay that
    /// includes its certificate fingerprint (pinned in Settings or learned on first connect),
    /// which the phone needs to pin the relay.
    func refreshPairingURI() {
        let mode = modeProvider()
        guard let fingerprint, RoomToken.isValid(roomToken) else {
            pairingURI = nil
            return
        }
        var endpoint: RelayEndpoint?
        var relayFingerprint: String?
        if mode.usesRelay {
            guard let relay = endpointProvider(), let pinned = relayFingerprintProvider() else {
                pairingURI = nil
                return
            }
            endpoint = relay
            relayFingerprint = pinned
        }
        var localPort: UInt16?
        if mode.usesLocal {
            guard let port = localPortProvider() else {
                pairingURI = nil
                return
            }
            localPort = port
        }
        pskLock.lock()
        let psk = pendingPSK
        pskLock.unlock()
        pairingURI = PairingPayload(
            deviceID: UUID().uuidString,
            deviceName: Host.current().localizedName ?? "Mac",
            host: endpoint?.host,
            port: endpoint?.port,
            room: roomToken,
            fingerprint: fingerprint,
            relayFingerprint: relayFingerprint,
            psk: psk,
            mode: mode,
            localHost: mode.usesLocal ? LocalNetworkAddress.primaryIPv4() : nil,
            localPort: localPort
        ).uri()
    }

    func retryRoomTokenLoad() {
        onStatus?("Can't read the pairing secrets — retrying")
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self else { return }
            let token = await Task.detached { Self.loadOrCreateRoomToken() }.value
            guard !token.isEmpty else {
                self.retryRoomTokenLoad()
                return
            }
            self.roomToken = token
            self.onRoomTokenRecovered?()
        }
    }

    /// Returns "" when the token file exists but can't be read:
    /// generating a replacement then would move the Mac to a new room and silently
    /// orphan the paired phone. `retryRoomTokenLoad` retries until the read succeeds.
    nonisolated private static func loadOrCreateRoomToken() -> String {
        #if DEBUG
        // Snapshots never touch the stored secrets.
        if SnapshotRenderer.isActive { return "snapshot-room-token" }
        #endif
        let stored: Data?
        do {
            stored = try SecretStore.default.load(roomTokenName)
        } catch {
            DebugLog.log("relay: room token load failed: \(error)")
            return ""
        }
        if let data = stored,
           let token = String(data: data, encoding: .utf8),
           RoomToken.isValid(token) {
            return token
        }
        let token = RoomToken.generate()
        try? SecretStore.default.save(Data(token.utf8), as: roomTokenName)
        return token
    }
}

#if DEBUG
extension PairingController {
    /// Fake pairing state for `SnapshotRenderer`; nothing touches the stored secrets.
    func applySnapshotFixture(pairingURI: String?, paired: Bool) {
        self.pairingURI = pairingURI
        isPaired = paired
    }
}
#endif
