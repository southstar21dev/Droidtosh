import Foundation
import DroidsyncKit

/// Owns phone status (PROTOCOL-v2.md §8) and Find my phone (§9): keeps the phone's latest
/// `DeviceStatus` for the popover, the device page and the menu bar, posts the low battery
/// alerts, and drives ring requests.
@MainActor
final class DeviceController: ObservableObject {
    @Published var showBatteryInMenuBar = UserDefaults.standard.bool(forKey: "device-battery-in-menu-bar") {
        didSet {
            guard showBatteryInMenuBar != oldValue else { return }
            UserDefaults.standard.set(showBatteryInMenuBar, forKey: "device-battery-in-menu-bar")
        }
    }
    @Published var lowBatteryAlertsEnabled = UserDefaults.standard.object(forKey: "device-low-battery-alerts") as? Bool ?? true {
        didSet {
            guard lowBatteryAlertsEnabled != oldValue else { return }
            UserDefaults.standard.set(lowBatteryAlertsEnabled, forKey: "device-low-battery-alerts")
            if !lowBatteryAlertsEnabled { presenter.remove(identifiers: [Self.lowBatteryIdentifier]) }
        }
    }
    @Published private(set) var status: DSDeviceStatus?
    @Published private(set) var receivedAt: Date?
    @Published private(set) var isStale = false
    @Published private(set) var findPhase: FindPhoneSession.Phase = .idle
    /// Whether the last phone that connected negotiated `find`; kept across disconnects (and
    /// launches) so the button can queue a ring while the phone is away.
    @Published private(set) var findKnownSupported = UserDefaults.standard.bool(forKey: "device-find-supported") {
        didSet {
            guard findKnownSupported != oldValue else { return }
            UserDefaults.standard.set(findKnownSupported, forKey: "device-find-supported")
        }
    }

    static let lowBatteryIdentifier = "device:low-battery"

    private let connection: ConnectionController
    private let presenter: NotificationPresenter
    private let tracker = PhoneStatusTracker()
    private let find = FindPhoneSession()
    private var timer: Timer?

    init(connection: ConnectionController, presenter: NotificationPresenter) {
        self.connection = connection
        self.presenter = presenter
    }

    /// The connected phone negotiated `phone.status`.
    var phoneSupportsStatus: Bool {
        connection.phoneConnected && connection.negotiatedCapabilities.contains(ProtocolV2.Capability.phoneStatus)
    }

    /// Show the status row: live from a supporting phone, or the last status greyed out.
    var showsStatus: Bool { phoneSupportsStatus || status != nil }

    /// The connected phone negotiated `find`.
    var phoneSupportsFind: Bool {
        connection.phoneConnected && connection.negotiatedCapabilities.contains(ProtocolV2.Capability.find)
    }

    /// Show the Find My Phone button: the phone supports it, or did when it was last connected.
    var showsFind: Bool { phoneSupportsFind || (!connection.phoneConnected && findKnownSupported) }

    var findButtonTitle: String { find.buttonTitle }
    var findStatusText: String? { find.statusText }
    var findCanStop: Bool { find.canStop }

    /// The percentage next to the status item icon, when enabled and fresh.
    var menuBarBatteryText: String? {
        guard showBatteryInMenuBar, let status, !isStale, status.batteryPercent >= 0 else { return nil }
        return PhoneStatusDisplay.batteryText(status)
    }

    var lastSeenText: String? {
        guard isStale, let receivedAt else { return nil }
        return PhoneStatusDisplay.lastSeenText(receivedAt, now: Date())
    }

    func start() {
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    /// The phone session is up (after the Resume exchange).
    func phoneConnected(capabilities: [String]) {
        if capabilities.contains(ProtocolV2.Capability.phoneStatus) {
            tracker.sessionStarted()
        } else {
            tracker.sessionEnded()
        }
        let findSupported = capabilities.contains(ProtocolV2.Capability.find)
        findKnownSupported = findSupported
        find.setConnected(findSupported)
        publish()
    }

    func phoneDisconnected() {
        tracker.sessionEnded()
        find.setConnected(false)
        publish()
    }

    func handle(_ envelope: DSEnvelope) {
        switch envelope.payload {
        case .deviceStatus(let newStatus):
            connection.diagnostics.recordDeviceActivity(.statusReceived)
            if let threshold = tracker.receive(newStatus) {
                DebugLog.log("device: battery \(newStatus.batteryPercent)% crossed \(threshold)%")
                if lowBatteryAlertsEnabled { postLowBattery(percent: newStatus.batteryPercent) }
            }
        case .findPhoneState(let state):
            DebugLog.log("device: find state \(state.requestID) ringing=\(state.ringing) ended=\(state.ended) error=\(state.error)")
            guard find.receive(state) else { break }
            if state.ringing {
                connection.diagnostics.recordDeviceActivity(.findRang)
            } else if state.ended == .findEndFailed {
                connection.diagnostics.recordDeviceActivity(.findFailed)
            }
        default:
            return
        }
        publish()
    }

    /// The Find My Phone / Stop Ringing button.
    func toggleFind() {
        if find.canStop {
            if let stop = find.stop() {
                // A START the phone never got (it was away) is taken back, so the reconnect
                // doesn't deliver START and STOP and ring for a moment.
                if connection.withdrawUnsent(where: { FindPhoneSession.isStart($0, requestID: stop.requestID) }) {
                    DebugLog.log("device: find stop \(stop.requestID) withdrew the queued start")
                } else {
                    DebugLog.log("device: find stop \(stop.requestID)")
                    connection.send(protocolEnvelope(.findPhoneRequest(stop)))
                }
            }
        } else {
            let request = find.start()
            DebugLog.log("device: find start \(request.requestID)")
            connection.diagnostics.recordDeviceActivity(.findRequested)
            // Reliable: queued in the outbox while the phone is away, sent on the next Resume.
            connection.send(protocolEnvelope(.findPhoneRequest(request)))
        }
        publish()
    }

    func resetForUnpair() {
        tracker.reset()
        find.reset()
        findKnownSupported = false
        presenter.remove(identifiers: [Self.lowBatteryIdentifier])
        publish()
    }

    private func tick() {
        if find.tick(), find.phase == .noResponse {
            connection.diagnostics.recordDeviceActivity(.findNoResponse)
        }
        publish()
    }

    private func postLowBattery(percent: Int32) {
        connection.diagnostics.recordDeviceActivity(.lowBatteryAlert)
        let name = connection.connectedDevice ?? "Your phone"
        presenter.postLocal(
            title: "Phone battery low",
            body: "\(name) is at \(percent)%. Plug it in soon.",
            identifier: Self.lowBatteryIdentifier
        )
    }

    /// Copies the models' state into the published properties, only where it changed.
    private func publish() {
        if status != tracker.status { status = tracker.status }
        if receivedAt != tracker.receivedAt { receivedAt = tracker.receivedAt }
        let stale = tracker.isStale()
        if isStale != stale { isStale = stale }
        if findPhase != find.phase { findPhase = find.phase }
    }
}
