import Foundation
import DroidsyncKit

/// The current transport, for senders that run off the main actor (file transfer). The
/// transport's `send` is thread-safe; this only guards which transport is current.
final class TransportSender: @unchecked Sendable {
    private let lock = NSLock()
    private var transport: RelayTransport?

    func set(_ transport: RelayTransport?) {
        lock.withLock { self.transport = transport }
    }

    func send(_ envelope: DSEnvelope) {
        lock.withLock { transport }?.send(envelope)
    }
}

/// What the transport was built with; a change rebuilds it.
private struct TransportConfig: Equatable {
    var relay: RelayEndpoint?
    var relayFingerprint: String?
    var localPort: UInt16?
}

/// Owns the phone transport's lifecycle (settings, rebuilds, sleep/wake, network changes) and the
/// connection state shown in the UI. Every outbound envelope goes through `send`.
@MainActor
final class ConnectionController: ObservableObject {
    typealias MacInfo = (name: String, model: String, identifier: String)

    @Published var statusText = "Starting…"
    @Published private(set) var connectedDevice: String?
    @Published private(set) var phoneConnected = false
    @Published private(set) var macName = ""
    @Published private(set) var macModel = ""
    @Published var relayHost: String = UserDefaults.standard.string(forKey: "relayHost") ?? "" {
        didSet {
            guard relayHost != oldValue else { return }
            UserDefaults.standard.set(relayHost, forKey: "relayHost")
            relaySettingsChanged()
        }
    }
    @Published var relayPortText: String = UserDefaults.standard.string(forKey: "relayPortText") ?? "7443" {
        didSet {
            guard relayPortText != oldValue else { return }
            UserDefaults.standard.set(relayPortText, forKey: "relayPortText")
            relaySettingsChanged()
        }
    }
    /// The relay certificate fingerprint to pin, as typed (or learned on first connect).
    /// Empty means trust on first use.
    @Published var relayFingerprintText: String = UserDefaults.standard.string(forKey: "relayFingerprint") ?? "" {
        didSet {
            guard relayFingerprintText != oldValue else { return }
            UserDefaults.standard.set(relayFingerprintText, forKey: "relayFingerprint")
            relaySettingsChanged()
        }
    }
    /// How the phone reaches this Mac: the relay, the local network, or both (PROTOCOL-v2.md §1.5).
    @Published var connectionMode: ConnectionMode = ConnectionMode(
        rawValue: UserDefaults.standard.string(forKey: "connectionMode") ?? ""
    ) ?? .relay {
        didSet {
            guard connectionMode != oldValue else { return }
            UserDefaults.standard.set(connectionMode.rawValue, forKey: "connectionMode")
            relaySettingsChanged()
        }
    }
    @Published var localPortText: String = UserDefaults.standard.string(forKey: "localPortText")
        ?? String(LocalListenerOptions.defaultPort) {
        didSet {
            guard localPortText != oldValue else { return }
            UserDefaults.standard.set(localPortText, forKey: "localPortText")
            relaySettingsChanged()
        }
    }
    /// The local listener's state; `.off` unless the mode uses the local network.
    @Published private(set) var localState: RelayTransport.LocalState = .off
    /// The path of the current phone session; nil while none is connected.
    @Published private(set) var sessionPath: SessionPath?
    /// Capabilities negotiated with the connected phone; empty while none is connected.
    @Published private(set) var negotiatedCapabilities: [String] = []

    /// The pinned relay fingerprint, or nil while it is unknown (nothing typed, not yet learned).
    var pinnedRelayFingerprint: String? { RelayFingerprint.normalized(relayFingerprintText) }

    var localPort: UInt16? { RelayEndpoint.port(from: localPortText) }

    /// Empty (trust on first use) or a valid fingerprint; anything else blocks connecting, so
    /// a half-typed value is never silently replaced by whatever certificate the relay shows.
    private var relayFingerprintIsUsable: Bool {
        relayFingerprintText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || pinnedRelayFingerprint != nil
    }

    /// Delivery counters of the reliable stream (for diagnostics).
    var reliabilityCounters: ReliableStream.Counters { reliableStream.counters }
    /// Reliable envelopes waiting for the phone's acknowledgement.
    var outboxDepth: Int { reliableStream.pendingCount }
    /// Process-wide counters and event log for the Diagnostics view; survives transport rebuilds.
    let diagnostics = DiagnosticsRecorder()
    /// The negotiated session while a phone is connected.
    var currentNegotiation: Negotiation? { relayTransport?.currentNegotiation }

    /// Sends from any thread to whichever transport is current.
    nonisolated let sender = TransportSender()

    /// The endpoint to join, combining the relay settings with the pairing's room token.
    var endpointProvider: () -> RelayEndpoint? = { nil }
    /// PSKs the handshake accepts; called on the relay's event loop.
    var pskProvider: () -> [Data] = { [] }
    /// Fires when the inputs to the pairing URI may have changed (settings edit, transport rebuild).
    var onPairingInputsChanged: (() -> Void)?
    var onAuthenticated: ((Data) -> Void)?
    var onPhoneDisconnected: (() -> Void)?
    var onEnvelope: ((DSEnvelope) -> Void)?

    /// Tracks `.connected` from the transport's state callbacks.
    private(set) var relayConnected = false
    /// The transport's live state, which can run ahead of `relayConnected`.
    var isTransportConnected: Bool { relayTransport?.state == .connected }

    private var relayTransport: RelayTransport?
    private var relayTransportGeneration: UInt64 = 0
    private var activeConfig: TransportConfig?
    /// One stream for the whole process, so queued reliable envelopes survive reconnects
    /// and transport rebuilds (PROTOCOL-v2.md §4).
    private let reliableStream = ReliableStream()
    private var tlsIdentity: TLSIdentity?
    private var macInfo: MacInfo?
    private var hostPowerObserver: HostPowerObserver?
    private var networkPathObserver: NetworkPathObserver?
    private var relayReconfigureTask: Task<Void, Never>?
    private var hasLaunched = false

    func start() {
        let powerObserver = HostPowerObserver(
            onSleep: { [weak self] in Task { @MainActor in self?.handleSleep() } },
            onWake: { [weak self] in Task { @MainActor in self?.handleWake() } }
        )
        powerObserver.start()
        hostPowerObserver = powerObserver
        let pathObserver = NetworkPathObserver { [weak self] in
            Task { @MainActor in self?.handleNetworkPathChange() }
        }
        pathObserver.start()
        networkPathObserver = pathObserver
    }

    func configure(identity: TLSIdentity, macInfo: MacInfo) {
        tlsIdentity = identity
        self.macInfo = macInfo
        macName = macInfo.name
        macModel = macInfo.model
    }

    /// Allows `applyRelaySettings` to start the transport; before launch it is a no-op.
    func markLaunched() {
        hasLaunched = true
    }

    func send(_ envelope: DSEnvelope) {
        relayTransport?.send(envelope)
    }

    /// Takes back a reliable envelope that is still only queued for the phone; see
    /// `RelayTransport.withdrawUnsent`.
    func withdrawUnsent(where match: (DSEnvelope) -> Bool) -> Bool {
        relayTransport?.withdrawUnsent(where: match) ?? false
    }

    func handle(_ envelope: DSEnvelope) {
        if case .hello(let hello)? = envelope.payload {
            let name = hello.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
            connectedDevice = name.isEmpty ? "Android phone" : name
        }
    }

    func markPhoneAuthenticated() {
        connectedDevice = "Android phone"
        phoneConnected = true
        statusText = "Connected"
    }

    func sendServerInfo() {
        guard let macInfo else { return }
        var info = DSServerInfo()
        info.deviceName = macInfo.name
        info.model = macInfo.model
        info.modelIdentifier = macInfo.identifier
        send(protocolEnvelope(.serverInfo(info)))
    }

    /// Clears the paired device and re-joins with the current settings (the room token has
    /// already been regenerated). The phone is marked disconnected without the disconnect hook.
    func resetForUnpair() {
        connectedDevice = nil
        phoneConnected = false
        activeConfig = nil
        applyRelaySettings()
    }

    func applyRelaySettings() {
        guard hasLaunched else { return }
        guard let config = desiredConfig() else {
            tearDownRelayTransport()
            activeConfig = nil
            onPairingInputsChanged?()
            return
        }
        guard config != activeConfig || relayTransport == nil else { return }
        rebuildRelayTransport(config)
    }

    /// The transport the settings call for, or nil (with `statusText` saying why) when they
    /// don't allow one. In `.both`, a relay that isn't set up yet leaves the local network alone.
    private func desiredConfig() -> TransportConfig? {
        var config = TransportConfig()
        if connectionMode.usesLocal {
            guard let localPort else {
                statusText = "Local network port is invalid"
                return nil
            }
            config.localPort = localPort
        }
        if connectionMode.usesRelay {
            if endpointProvider() == nil {
                guard connectionMode == .both else {
                    statusText = "Relay not configured"
                    return nil
                }
            } else if !relayFingerprintIsUsable {
                guard connectionMode == .both else {
                    statusText = "Relay fingerprint is invalid — paste the 64-character value from the relay log"
                    return nil
                }
            } else {
                config.relay = endpointProvider()
                config.relayFingerprint = pinnedRelayFingerprint
            }
        }
        return config
    }

    private func rebuildRelayTransport(_ config: TransportConfig) {
        guard let identity = tlsIdentity else { return }
        tearDownRelayTransport()
        activeConfig = config
        relayTransportGeneration &+= 1
        let gen = relayTransportGeneration
        let local = config.localPort.map {
            LocalListenerOptions(
                port: Int($0),
                advertisement: LocalAdvertisement(name: macName.isEmpty ? "Mac" : macName, fingerprint: identity.fingerprint)
            )
        }
        let transport = RelayTransport(
            relay: config.relay,
            local: local,
            identity: identity,
            pskProvider: pskProvider,
            relayFingerprint: config.relayFingerprint,
            reliableStream: reliableStream,
            diagnostics: diagnostics,
            onStateChange: { [weak self] state in
                Task { @MainActor in
                    guard let self, self.relayTransportGeneration == gen else { return }
                    self.handle(state: state)
                }
            },
            onClientAuthenticated: { [weak self] session in
                Task { @MainActor in
                    guard let self, self.relayTransportGeneration == gen else { return }
                    self.negotiatedCapabilities = session.negotiation.capabilities
                    self.sessionPath = session.path
                    DebugLog.log("relay: negotiated v\(session.negotiation.version) \(session.negotiation.capabilities) via \(session.path.rawValue)")
                    self.onAuthenticated?(session.psk)
                }
            },
            onClientDisconnected: { [weak self] in
                Task { @MainActor in
                    guard let self, self.relayTransportGeneration == gen else { return }
                    self.handlePhoneDisconnected()
                }
            },
            onEnvelope: { [weak self] envelope in
                Task { @MainActor in
                    guard let self, self.relayTransportGeneration == gen else { return }
                    self.onEnvelope?(envelope)
                }
            }
        )
        transport.onDebugLog = { message in
            DebugLog.log("relay: \(message)")
        }
        transport.onRelayFingerprintLearned = { [weak self] fingerprint in
            Task { @MainActor in
                guard let self, self.relayTransportGeneration == gen else { return }
                self.learnRelayFingerprint(fingerprint)
            }
        }
        transport.onLocalStateChange = { [weak self] state in
            Task { @MainActor in
                guard let self, self.relayTransportGeneration == gen else { return }
                self.localState = state
                if let state = self.relayTransport?.state { self.handle(state: state) }
            }
        }
        relayTransport = transport
        sender.set(transport)
        transport.start()
        onPairingInputsChanged?()
    }

    /// Stops the current transport and runs the disconnect clean-up directly: callbacks
    /// from a replaced transport are ignored, so its own `onClientDisconnected` never
    /// reaches this controller.
    private func tearDownRelayTransport() {
        guard let transport = relayTransport else { return }
        relayTransportGeneration &+= 1
        transport.stop()
        relayTransport = nil
        sender.set(nil)
        relayConnected = false
        localState = .off
        if phoneConnected { handlePhoneDisconnected() }
    }

    /// Trust on first use: the running transport already pins what it saw, so recording it
    /// as the active fingerprint keeps the settings change from rebuilding the transport.
    private func learnRelayFingerprint(_ fingerprint: String) {
        guard relayFingerprintText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        DebugLog.log("relay: pinned relay certificate \(fingerprint)")
        activeConfig?.relayFingerprint = fingerprint
        relayFingerprintText = fingerprint
    }

    private func handlePhoneDisconnected() {
        DebugLog.log("relay: client disconnected")
        phoneConnected = false
        negotiatedCapabilities = []
        sessionPath = nil
        onPhoneDisconnected?()
    }

    private func relaySettingsChanged() {
        onPairingInputsChanged?()
        relayReconfigureTask?.cancel()
        relayReconfigureTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled else { return }
            self?.applyRelaySettings()
        }
    }

    /// Drops the current relay connection (and any phone session) and dials again at once.
    func reconnectNow() {
        DebugLog.log("relay: reconnect requested from diagnostics")
        diagnostics.recordEvent(.state, "Reconnect requested")
        guard hasLaunched, let config = desiredConfig() else {
            applyRelaySettings()
            return
        }
        rebuildRelayTransport(config)
    }

    private func handleSleep() {
        if relayTransport?.state == .connected {
            var status = DSHostStatus()
            status.sleeping = true
            relayTransport?.send(protocolEnvelope(.hostStatus(status)))
        }
        relayTransport?.suspend()
    }

    private func handleWake() {
        if let transport = relayTransport {
            transport.resume()
        } else {
            applyRelaySettings()
        }
    }

    private func handleNetworkPathChange() {
        DebugLog.log("relay: network path changed")
        relayTransport?.reconnectIfIdle()
    }

    private var localListening: Bool {
        if case .listening = localState { return true }
        return false
    }

    private func handle(state: RelayTransport.State) {
        relayConnected = state == .connected
        switch state {
        case .stopped:
            guard relayTransport != nil else { return }
            statusText = "Stopped"
        case .connecting:
            statusText = localListening ? "Waiting for phone on the local network" : "Connecting to relay…"
        case .waitingForPhone:
            statusText = "Waiting for phone"
        case .connected:
            statusText = "Connected"
        case .failed(let message):
            DebugLog.log("relay: state failed \(message)")
            if activeConfig?.relay == nil {
                statusText = "Local network unavailable — retrying"
            } else if localListening {
                statusText = "Waiting for phone on the local network — relay is down"
            } else {
                statusText = "Relay unreachable — retrying"
            }
        case .relayCertificateMismatch(let observed):
            DebugLog.log("relay: certificate mismatch, relay presented \(observed)")
            statusText = "Relay certificate changed — check the fingerprint in Settings"
        case .relayRequiresUpgrade:
            statusText = "Relay needs a newer Droidtosh — update the app"
        }
    }
}

#if DEBUG
extension ConnectionController {
    /// Fake connection state for `SnapshotRenderer`; nothing is saved.
    func applySnapshotFixture(device: String?, capabilities: [String], macName: String, macModel: String, status: String) {
        connectedDevice = device
        phoneConnected = device != nil && status == "Connected"
        negotiatedCapabilities = phoneConnected ? capabilities : []
        self.macName = macName
        self.macModel = macModel
        statusText = status
    }
}
#endif
