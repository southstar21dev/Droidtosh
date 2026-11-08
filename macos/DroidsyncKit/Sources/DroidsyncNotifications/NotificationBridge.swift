import CryptoKit
import Foundation
import DroidsyncProtocol

public final class NotificationBridge {
    public static let categoryPrefix = "app."
    public static let replyPrefix = "reply."
    public static let actionPrefix = "action."
    public static let maxCustomActions = 4

    private let presenter: NotificationPresenting
    private var presentedKeys: Set<String> = []

    public init(presenter: NotificationPresenting) {
        self.presenter = presenter
    }

    @discardableResult
    public func handlePosted(_ record: DSNotificationRecord) -> PendingNotification {
        var pending = Self.pending(from: record)
        pending.silent = presentedKeys.contains(record.key)
        presentedKeys.insert(record.key)
        presenter.post(pending)
        return pending
    }

    public func handleRemoved(key: String) {
        presentedKeys.remove(key)
        presenter.remove(identifiers: [key])
    }

    public func reset() {
        presentedKeys.removeAll()
        presenter.removeAll()
    }

    public func reconcile(with records: [DSNotificationRecord]) {
        let incoming = Set(records.map(\.key))
        let stale = presentedKeys.subtracting(incoming)
        if !stale.isEmpty {
            presentedKeys.subtract(stale)
            presenter.remove(identifiers: Array(stale))
        }
        for record in records {
            var pending = Self.pending(from: record)
            pending.silent = presentedKeys.contains(record.key)
            presentedKeys.insert(record.key)
            presenter.post(pending)
        }
    }

    public static func pending(from record: DSNotificationRecord) -> PendingNotification {
        let replies = record.actions.filter(\.hasRemoteInput_p)
        let customActions = record.actions.filter { !$0.hasRemoteInput_p }
        let selectedActions = Array((replies + customActions).prefix(maxCustomActions))
        let actions = selectedActions.map { action in
            PendingAction(
                identifier: actionIdentifier(for: action),
                title: action.title,
                isReply: action.hasRemoteInput_p
            )
        }
        let replyPlaceholder = record.remoteInputHint.isEmpty ? "Reply" : record.remoteInputHint
        // Categories are registered once per identifier with their button titles, so the
        // titles and placeholder are hashed into the identifier to keep a category from
        // being reused with another notification's labels.
        let actionSignature = selectedActions.isEmpty
            ? "none"
            : selectedActions.map { ($0.hasRemoteInput_p ? "r" : "a") + String($0.index) }
                .joined(separator: ",") + "." + labelHash(
                    selectedActions.map(\.title) + (replies.isEmpty ? [] : [replyPlaceholder])
                )
        let subtitleParts = [record.appLabel, record.subText].filter { !$0.isEmpty }
        return PendingNotification(
            identifier: record.key,
            packageName: record.packageName,
            title: record.title,
            body: record.bigText.isEmpty ? record.text : record.bigText,
            subtitle: subtitleParts.joined(separator: " · "),
            categoryIdentifier: categoryPrefix + record.packageName + "." + actionSignature,
            isOngoing: record.isOngoing,
            replyPlaceholder: replyPlaceholder,
            actions: actions
        )
    }

    private static func labelHash(_ labels: [String]) -> String {
        let digest = SHA256.hash(data: Data(labels.joined(separator: "\u{1F}").utf8))
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    public static func actionIdentifier(for action: DSActionInfo) -> String {
        action.hasRemoteInput_p
            ? replyPrefix + String(action.index)
            : actionPrefix + String(action.index)
    }

    public static func actionEnvelope(key: String, identifier: String, replyText: String?) -> DSEnvelope? {
        let index: UInt32
        let reply: String
        if identifier.hasPrefix(replyPrefix),
           let parsed = UInt32(identifier.dropFirst(replyPrefix.count)) {
            index = parsed
            reply = replyText ?? ""
        } else if identifier.hasPrefix(actionPrefix),
                  let parsed = UInt32(identifier.dropFirst(actionPrefix.count)) {
            index = parsed
            reply = ""
        } else {
            return nil
        }
        var request = DSNotificationAction()
        request.key = key
        request.actionIndex = index
        request.replyText = reply
        request.requestID = UUID().uuidString
        return protocolEnvelope(.notificationAction(request))
    }

    public static func dismissEnvelope(key: String) -> DSEnvelope {
        var dismiss = DSNotificationDismiss()
        dismiss.key = key
        return protocolEnvelope(.notificationDismiss(dismiss))
    }
}
