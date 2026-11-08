import AppKit
import Combine
import Foundation
import DroidsyncKit

/// Composition root: builds the feature controllers, wires them together, and routes each
/// inbound envelope to the controllers that own its payload (see `EnvelopeRoute`).
/// Views observe this object; it re-publishes every controller's changes.
@MainActor
final class AppModel: ObservableObject {
    let connection: ConnectionController
    let pairing: PairingController
    let notifications: NotificationsController
    let calls: CallsController
    let callLog: CallLogController
    let clipboard: ClipboardController
    let files: FilesController
    let device: DeviceController
    let updates = UpdatesController()
    /// Not re-published through this object: it refreshes every second while visible, and
    /// `DiagnosticsView` observes it directly.
    let diagnostics: DiagnosticsController

    private let notificationPresenter: NotificationPresenter
    private let callPanel = CallPanelController()
    private var controllerChanges: [AnyCancellable] = []

    init() {
        LegacyDefaultsMigration.run()
        let presenter = NotificationPresenter()
        let connection = ConnectionController()
        let send: (DSEnvelope) -> Void = { [weak connection] envelope in connection?.send(envelope) }
        notificationPresenter = presenter
        self.connection = connection
        let pairing = PairingController()
        self.pairing = pairing
        diagnostics = DiagnosticsController(connection: connection, pairing: pairing)
        notifications = NotificationsController(presenter: presenter, send: send)
        calls = CallsController(presenter: presenter, send: send)
        callLog = CallLogController(send: send)
        clipboard = ClipboardController(connection: connection, presenter: presenter)
        files = FilesController(connection: connection, presenter: presenter)
        device = DeviceController(connection: connection, presenter: presenter)
        controllerChanges = [
            connection.objectWillChange,
            pairing.objectWillChange,
            notifications.objectWillChange,
            calls.objectWillChange,
            callLog.objectWillChange,
            clipboard.objectWillChange,
            files.objectWillChange,
            device.objectWillChange,
            updates.objectWillChange,
        ].map { $0.sink { [weak self] _ in self?.objectWillChange.send() } }
        wireControllers()
    }

    var relayEndpoint: RelayEndpoint? {
        RelayEndpoint(host: connection.relayHost, portText: connection.relayPortText, room: pairing.roomToken)
    }

    var relayConfigured: Bool { relayEndpoint != nil }

    /// Whether the settings let a phone pair: local-only needs no relay, the other modes do.
    var connectionConfigured: Bool {
        switch connection.connectionMode {
        case .local: return connection.localPort != nil
        case .relay: return relayConfigured
        case .both: return relayConfigured && connection.localPort != nil
        }
    }

    func send(_ envelope: DSEnvelope) {
        connection.send(envelope)
    }

    private func wireControllers() {
        connection.endpointProvider = { [weak self] in self?.relayEndpoint }
        connection.pskProvider = { [pairing] in pairing.pskCandidates() }
        connection.onPairingInputsChanged = { [weak self] in self?.pairing.refreshPairingURI() }
        connection.onAuthenticated = { [weak self] psk in self?.handleAuthenticated(psk: psk) }
        connection.onPhoneDisconnected = { [weak self] in self?.handlePhoneDisconnected() }
        connection.onEnvelope = { [weak self] envelope in self?.handle(envelope: envelope) }

        pairing.endpointProvider = { [weak self] in self?.relayEndpoint }
        pairing.relayFingerprintProvider = { [weak self] in self?.connection.pinnedRelayFingerprint }
        pairing.modeProvider = { [weak self] in self?.connection.connectionMode ?? .relay }
        pairing.localPortProvider = { [weak self] in self?.connection.localPort }
        pairing.onStatus = { [weak self] status in self?.connection.statusText = status }
        pairing.onRoomTokenRecovered = { [weak self] in
            guard let self else { return }
            self.connection.applyRelaySettings()
            self.pairing.refreshPairingURI()
        }

        calls.onActiveCallChanged = { [weak self] snapshot in
            guard let self else { return }
            self.callPanel.update(model: self, visible: snapshot != nil)
        }
        calls.onActionSent = { [weak self] requestID, package in
            self?.notifications.registerActionRequest(requestID: requestID, package: package)
        }
        calls.onCallFinished = { [weak self] in self?.callLog.request(reset: true) }
    }

    func start() {
        notificationPresenter.start()
        notificationPresenter.onAuthorizationCompleted = { [weak self] in
            self?.notifications.refreshNotificationStatus()
        }
        notifications.refreshNotificationStatus()
        connection.start()
        updates.start()
        Task.detached(priority: .userInitiated) { [self] in
            let macIdentity = MacIdentity.current()
            do {
                let identity = try TLSIdentityStore.loadOrCreate()
                await MainActor.run { launch(identity: identity, macIdentity: macIdentity) }
            } catch {
                await MainActor.run { connection.statusText = "Identity error: \(error)" }
            }
        }
    }

    private func launch(identity: TLSIdentity, macIdentity: ConnectionController.MacInfo) {
        connection.configure(identity: identity, macInfo: macIdentity)
        pairing.loadSecrets(fingerprint: identity.fingerprint)
        notificationPresenter.onResponse = { [weak self] key, identifier, replyText, isDismiss in
            Task { @MainActor in
                self?.handleNotificationResponse(key: key, identifier: identifier, replyText: replyText, isDismiss: isDismiss)
            }
        }
        notifications.launch()
        calls.launch()
        clipboard.start()
        files.start()
        device.start()
        connection.markLaunched()
        connection.applyRelaySettings()
        pairing.refreshPairingURI()
        if pairing.roomToken.isEmpty { pairing.retryRoomTokenLoad() }
    }

    private func handle(envelope: DSEnvelope) {
        guard let payload = envelope.payload else { return }
        for feature in EnvelopeRoute.features(for: payload) {
            switch feature {
            case .connection: connection.handle(envelope)
            case .notifications: notifications.handle(envelope)
            case .calls: calls.handle(envelope)
            case .callLog: callLog.handle(envelope)
            case .clipboard: clipboard.handle(envelope)
            case .files: files.handle(envelope)
            case .device: device.handle(envelope)
            }
        }
    }

    private func handleNotificationResponse(key: String, identifier: String, replyText: String?, isDismiss: Bool) {
        if identifier.hasPrefix("call.") {
            calls.performCallAction(identifier)
        } else if key.hasPrefix("device:") {
            // Low battery alert: nothing to do on the phone.
            return
        } else if key.hasPrefix("file:") {
            if !isDismiss { files.handleNotificationResponse(key: key) }
        } else if identifier == "clipboard.sendImage" {
            clipboard.sendPendingImage()
        } else if identifier == "open-on-phone" || (!isDismiss && key.hasPrefix("openapp:")) {
            let package = key.hasPrefix("openapp:") ? String(key.dropFirst("openapp:".count)) : ""
            if !package.isEmpty { notifications.openApp(package: package) }
        } else if isDismiss {
            notifications.handleUserDismiss(key: key)
        } else {
            notifications.handleUserAction(key: key, identifier: identifier, replyText: replyText)
        }
    }

    private func handleAuthenticated(psk: Data) {
        pairing.handleAuthenticated(psk: psk)
        connection.markPhoneAuthenticated()
        notifications.phoneAuthenticated()
        connection.sendServerInfo()
        calls.reset()
        files.phoneConnected(capabilities: connection.negotiatedCapabilities)
        device.phoneConnected(capabilities: connection.negotiatedCapabilities)
    }

    private func handlePhoneDisconnected() {
        clipboard.phoneDisconnected()
        files.phoneDisconnected()
        device.phoneDisconnected()
        calls.reset()
    }

    func unpair() {
        pairing.forgetPairing()
        calls.resetForUnpair()
        callPanel.update(model: self, visible: false)
        callLog.reset()
        device.resetForUnpair()
        notifications.resetForUnpair()
        connection.resetForUnpair()
        pairing.refreshPairingURI()
    }
}
