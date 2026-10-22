import AppKit
import Foundation
import DroidsyncKit

/// Owns notification relaying: the coordinator, mute list, notification dedup settings, the
/// phone's installed apps and the notification permission state.
@MainActor
final class NotificationsController: ObservableObject {
    @Published private(set) var mutedPackages: Set<String> = []
    @Published private(set) var installedApps: [DSAppInfo] = []
    @Published private(set) var dedupEnabled = true
    @Published private(set) var dedupDisabledPackages: Set<String> = []
    @Published private(set) var notificationsRelayEnabled = true
    @Published private(set) var notificationsAuthorized: Bool?
    /// The phone's labels for its apps, from the app list and from notification records.
    @Published private(set) var appLabels: [String: String] = [:]

    private let presenter: NotificationPresenter
    private let send: (DSEnvelope) -> Void
    private var coordinator: NotificationCoordinator?

    init(presenter: NotificationPresenter, send: @escaping (DSEnvelope) -> Void) {
        self.presenter = presenter
        self.send = send
    }

    var voipApps: [DSAppInfo] {
        installedApps.filter { VoipApps.knownPackages.contains($0.packageName) }
    }

    func launch() {
        let defaults = UserDefaults.standard
        dedupEnabled = defaults.object(forKey: "dedup-enabled") as? Bool ?? true
        dedupDisabledPackages = Set(defaults.stringArray(forKey: "dedup-disabled") ?? [])
        let dedup = DedupEngine(
            config: DedupConfig(enabled: dedupEnabled, disabledPackages: dedupDisabledPackages),
            runningApps: { RunningApps.bundleIDs() }
        )
        let coordinator = NotificationCoordinator(
            presenter: presenter,
            dedup: dedup,
            muteList: MuteListStore()
        )
        coordinator.attach(send: send)
        self.coordinator = coordinator
        mutedPackages = coordinator.mutedPackages
    }

    /// "WhatsApp Business" for `com.whatsapp.w4b`: the phone's label, a known name, or one made
    /// from the package, never the raw package.
    func appName(for packageName: String) -> String {
        AppNames.displayName(for: packageName, labels: appLabels)
    }

    private func learnLabels(from envelope: DSEnvelope) {
        var found: [(String, String)] = []
        switch envelope.payload {
        case .notificationPosted(let posted)?: found = [(posted.record.packageName, posted.record.appLabel)]
        case .notificationUpdated(let updated)?: found = [(updated.record.packageName, updated.record.appLabel)]
        case .snapshotReply(let snapshot)?: found = snapshot.notifications.map { ($0.packageName, $0.appLabel) }
        case .appsList(let list)?: found = list.apps.map { ($0.packageName, $0.label) }
        default: return
        }
        var labels = appLabels
        for (package, label) in found where !label.isEmpty && label != package {
            labels[package] = label
        }
        if labels != appLabels { appLabels = labels }
    }

    func handle(_ envelope: DSEnvelope) {
        learnLabels(from: envelope)
        if case .relayStatus(let status)? = envelope.payload {
            notificationsRelayEnabled = status.notificationsEnabled
            return
        }
        coordinator?.handle(envelope)
        if case .appsList? = envelope.payload { installedApps = coordinator?.installedApps ?? [] }
        if case .snapshotReply? = envelope.payload { mutedPackages = coordinator?.mutedPackages ?? [] }
    }

    /// Asks a freshly authenticated phone for its notifications and sends it the mute list.
    func phoneAuthenticated() {
        coordinator?.prepareForFirstReconcile()
        coordinator?.sendSnapshotRequest()
        coordinator?.sendMuteList()
        mutedPackages = coordinator?.mutedPackages ?? []
    }

    func resetForUnpair() {
        installedApps = []
        appLabels = [:]
    }

    func handleUserAction(key: String, identifier: String, replyText: String?) {
        coordinator?.handleUserAction(key: key, identifier: identifier, replyText: replyText)
    }

    func handleUserDismiss(key: String) {
        coordinator?.handleUserDismiss(key: key)
    }

    func registerActionRequest(requestID: String, package: String) {
        coordinator?.registerActionRequest(requestID: requestID, package: package)
    }

    func requestAppsList() { coordinator?.requestAppsList() }

    func setMuted(package: String, muted: Bool) {
        coordinator?.setMuted(package: package, muted: muted)
        mutedPackages = coordinator?.mutedPackages ?? []
    }

    func openApp(package: String) {
        var request = DSOpenAppRequest()
        request.packageName = package
        send(protocolEnvelope(.openAppRequest(request)))
    }

    func refreshNotificationStatus() {
        presenter.refreshAuthorizationStatus { [weak self] allowed in
            Task { @MainActor in self?.notificationsAuthorized = allowed }
        }
    }

    func setDedupEnabled(_ enabled: Bool) {
        dedupEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "dedup-enabled")
        coordinator?.updateDedup(config: DedupConfig(enabled: enabled, disabledPackages: dedupDisabledPackages))
    }

    func setDedupDisabled(package: String, disabled: Bool) {
        if disabled {
            dedupDisabledPackages.insert(package)
        } else {
            dedupDisabledPackages.remove(package)
        }
        UserDefaults.standard.set(Array(dedupDisabledPackages).sorted(), forKey: "dedup-disabled")
        coordinator?.updateDedup(
            config: DedupConfig(enabled: dedupEnabled, disabledPackages: dedupDisabledPackages)
        )
    }
}

enum RunningApps {
    static func bundleIDs() -> Set<String> {
        Set(NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier))
    }
}

#if DEBUG
extension NotificationsController {
    /// Fake notification state for `SnapshotRenderer`; nothing is saved or sent.
    func applySnapshotFixture(apps: [DSAppInfo], muted: Set<String>, authorized: Bool?, relayEnabled: Bool) {
        installedApps = apps
        mutedPackages = muted
        notificationsAuthorized = authorized
        notificationsRelayEnabled = relayEnabled
    }
}
#endif
