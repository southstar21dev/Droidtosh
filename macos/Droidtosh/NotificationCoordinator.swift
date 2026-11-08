import Foundation
import DroidsyncKit

@MainActor
final class NotificationCoordinator {
    private let presenter: NotificationPresenter
    private let bridge: NotificationBridge
    private let dedup: DedupEngine
    private let muteList: MuteListStore

    private var send: ((DSEnvelope) -> Void)?
    private var iconURLs: [String: URL] = [:]
    private var iconRequestedAt: [String: Date] = [:]
    private var hasReconciledOnce = false
    private var packageByKey: [String: String] = [:]
    private var packageByRequestID: [String: String] = [:]

    private(set) var installedApps: [DSAppInfo] = []

    init(
        presenter: NotificationPresenter,
        dedup: DedupEngine,
        muteList: MuteListStore
    ) {
        self.presenter = presenter
        self.bridge = NotificationBridge(presenter: presenter)
        self.dedup = dedup
        self.muteList = muteList
    }

    func attach(send: @escaping (DSEnvelope) -> Void) {
        self.send = send
    }

    var mutedPackages: Set<String> { muteList.mutedPackages }

    func handle(_ envelope: DSEnvelope) {
        switch envelope.payload {
        case .notificationPosted(let posted)?:
            deliver(posted.record)
        case .notificationUpdated(let updated)?:
            deliver(updated.record)
        case .notificationRemoved(let removed)?:
            packageByKey.removeValue(forKey: removed.key)
            bridge.handleRemoved(key: removed.key)
        case .snapshotReply(let snapshot)?:
            reconcile(snapshot.notifications)
        case .appsList(let list)?:
            installedApps = list.apps
        case .appIcon(let icon)?:
            if icon.png.isEmpty {
                iconRequestedAt.removeValue(forKey: icon.packageName)
            } else {
                cacheIcon(icon)
            }
        case .actionResult(let result)?:
            let package = packageByRequestID.removeValue(forKey: result.requestID)
            DebugLog.log("call: actionResult ok=\(result.ok) code=\(result.errorCode) message=\(result.message)")
            if !result.ok {
                let detail = result.message.isEmpty
                    ? result.errorCode
                    : "\(result.errorCode): \(result.message)"
                presentActionFailure(package: package, detail: detail)
            }
        default:
            break
        }
    }

    func handleUserAction(key: String, identifier: String, replyText: String?) {
        if let envelope = NotificationBridge.actionEnvelope(key: key, identifier: identifier, replyText: replyText) {
            if case .notificationAction(let request) = envelope.payload,
               let package = packageByKey[key] {
                packageByRequestID[request.requestID] = package
            }
            send?(envelope)
        }
    }

    func handleUserDismiss(key: String) {
        // Local failure notices have no counterpart on the phone.
        guard !key.hasPrefix("droidsync-action-failure-"), !key.hasPrefix("openapp:") else { return }
        send?(NotificationBridge.dismissEnvelope(key: key))
    }

    private func presentActionFailure(package: String?, detail: String) {
        let body = detail.isEmpty
            ? "Couldn't perform that action on your phone."
            : "Couldn't perform that action on your phone. (\(detail))"
        guard let package else {
            var record = DSNotificationRecord()
            record.key = "droidsync-action-failure-\(UUID().uuidString)"
            record.packageName = "droidsync"
            record.title = "Droidtosh"
            record.text = body
            presenter.post(NotificationBridge.pending(from: record))
            return
        }
        presenter.post(PendingNotification(
            identifier: "openapp:\(package)",
            packageName: package,
            title: "Droidtosh",
            body: body,
            subtitle: "",
            categoryIdentifier: "droidsync.action-failure.openapp",
            isOngoing: false,
            replyPlaceholder: "",
            actions: [PendingAction(identifier: "open-on-phone", title: "Open on phone", isReply: false)]
        ))
    }

    func registerActionRequest(requestID: String, package: String) {
        guard !package.isEmpty else { return }
        packageByRequestID[requestID] = package
    }

    func prepareForFirstReconcile() {
        guard !hasReconciledOnce else { return }
        hasReconciledOnce = true
        bridge.reset()
    }

    func sendSnapshotRequest() {
        send?(protocolEnvelope(.snapshotRequest(DSSnapshotRequest())))
    }

    func sendMuteList() {
        var mute = DSMuteListSet()
        mute.packages = Array(muteList.mutedPackages)
        send?(protocolEnvelope(.muteListSet(mute)))
    }

    func requestAppsList() {
        send?(protocolEnvelope(.appsListRequest(DSAppsListRequest())))
    }

    func setMuted(package: String, muted: Bool) {
        muteList.setMuted(package, muted)
        sendMuteList()
    }

    private func shouldDrop(_ record: DSNotificationRecord) -> Bool {
        if muteList.mutedPackages.contains(record.packageName) { return true }
        if dedup.shouldSuppress(androidPackage: record.packageName) { return true }
        return false
    }

    private func deliver(_ record: DSNotificationRecord) {
        guard !shouldDrop(record) else { return }
        packageByKey[record.key] = record.packageName
        if !record.iconPng.isEmpty {
            cacheIconData(package: record.packageName, data: record.iconPng)
        }
        bridge.handlePosted(record)
        requestIconIfNeeded(package: record.packageName)
    }

    private func reconcile(_ records: [DSNotificationRecord]) {
        let kept = records.filter { !shouldDrop($0) }
        let incoming = Set(kept.map(\.key))
        packageByKey = packageByKey.filter { incoming.contains($0.key) }
        kept.forEach { packageByKey[$0.key] = $0.packageName }
        for record in kept where !record.iconPng.isEmpty {
            cacheIconData(package: record.packageName, data: record.iconPng)
        }
        bridge.reconcile(with: kept)
        records.forEach { requestIconIfNeeded(package: $0.packageName) }
    }

    private func requestIconIfNeeded(package: String) {
        guard iconURLs[package] == nil else { return }
        if let requestedAt = iconRequestedAt[package], Date().timeIntervalSince(requestedAt) < 20 { return }
        iconRequestedAt[package] = Date()
        var request = DSAppIcon()
        request.packageName = package
        DebugLog.log("icon: request pkg=\(package)")
        send?(protocolEnvelope(.appIcon(request)))
    }

    private func cacheIcon(_ icon: DSAppIcon) {
        cacheIconData(package: icon.packageName, data: icon.png)
    }

    private func cacheIconData(package: String, data: Data) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("droidsync-icon-\(package).png")
        guard (try? data.write(to: url)) != nil else {
            DebugLog.log("icon: write failed pkg=\(package)")
            return
        }
        iconURLs[package] = url
        iconRequestedAt.removeValue(forKey: package)
        DebugLog.log("icon: cached pkg=\(package) bytes=\(data.count)")
        presenter.attachmentProvider = { [weak self] packageName in
            self?.iconURLs[packageName]
        }
    }

    func updateDedup(config: DedupConfig) {
        dedup.update(config: config)
    }
}
