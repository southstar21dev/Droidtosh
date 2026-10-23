import AppKit
import UserNotifications
import DroidsyncKit

final class NotificationPresenter: NSObject, NotificationPresenting, UNUserNotificationCenterDelegate {
    var onResponse: ((_ key: String, _ identifier: String, _ replyText: String?, _ isDismiss: Bool) -> Void)?
    var attachmentProvider: ((String) -> URL?)?
    var onAuthorizationCompleted: (() -> Void)?

    private let center = UNUserNotificationCenter.current()
    private var categoriesByID: [String: UNNotificationCategory] = [:]

    func start() {
        center.delegate = self
        categoriesByID["clipboard.sendImage"] = UNNotificationCategory(
            identifier: "clipboard.sendImage",
            actions: [UNNotificationAction(identifier: "clipboard.sendImage", title: "Send", options: [])],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories(Set(categoriesByID.values))
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] _, _ in
            DispatchQueue.main.async {
                self?.onAuthorizationCompleted?()
            }
        }
        center.getNotificationSettings { settings in
            DebugLog.log("call: authorization status=\(settings.authorizationStatus.rawValue) alertStyle=\(settings.alertStyle.rawValue)")
        }
    }

    func refreshAuthorizationStatus(_ completion: @escaping (Bool?) -> Void) {
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional:
                completion(true)
            case .denied:
                completion(false)
            case .notDetermined:
                completion(nil)
            @unknown default:
                completion(nil)
            }
        }
    }

    func post(_ notification: PendingNotification) {
        let registersCategory = categoriesByID[notification.categoryIdentifier] == nil
        if registersCategory {
            categoriesByID[notification.categoryIdentifier] = makeCategory(for: notification)
            center.setNotificationCategories(Set(categoriesByID.values))
        }

        let content = UNMutableNotificationContent()
        content.title = notification.title
        content.body = notification.body
        content.subtitle = notification.subtitle
        content.categoryIdentifier = notification.categoryIdentifier
        content.interruptionLevel = notification.silent ? .passive : .active
        content.userInfo = ["key": notification.identifier]
        if let url = attachmentProvider?(notification.packageName) {
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent("droidsync-attach-\(UUID().uuidString).png")
            let copied = (try? FileManager.default.copyItem(at: url, to: copy)) != nil
            if copied,
               let attachment = try? UNNotificationAttachment(identifier: "icon", url: copy, options: nil) {
                content.attachments = [attachment]
                DebugLog.log("icon: attach ok pkg=\(notification.packageName)")
            } else {
                DebugLog.log("icon: attach failed pkg=\(notification.packageName) copied=\(copied)")
            }
        }
        DebugLog.log("call: presenter.post id=\(notification.identifier) category=\(notification.categoryIdentifier)")
        let request = UNNotificationRequest(identifier: notification.identifier, content: content, trigger: nil)
        let add = { [center] in
            center.add(request) { error in
                if let error {
                    DebugLog.log("call: center.add ERROR id=\(notification.identifier) error=\(error)")
                } else {
                    DebugLog.log("call: center.add OK id=\(notification.identifier)")
                }
            }
        }
        if registersCategory {
            // setNotificationCategories applies asynchronously; reading the categories
            // back waits for it, so the first notification of a category gets its buttons.
            center.getNotificationCategories { _ in add() }
        } else {
            add()
        }
    }

    func postLocal(title: String, body: String, identifier: String, categoryIdentifier: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let categoryIdentifier {
            content.categoryIdentifier = categoryIdentifier
        }
        content.userInfo = ["key": identifier]
        DebugLog.log("clipboard: presenter.postLocal id=\(identifier)")
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
            if let error {
                DebugLog.log("clipboard: center.add ERROR id=\(identifier) error=\(error)")
            } else {
                DebugLog.log("clipboard: center.add OK id=\(identifier)")
            }
        }
    }

    func remove(identifiers: [String]) {
        DebugLog.log("call: presenter.remove \(identifiers)")
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func removeAll() {
        center.removeAllDeliveredNotifications()
    }

    private func makeCategory(for notification: PendingNotification) -> UNNotificationCategory {
        let actions: [UNNotificationAction] = notification.actions.map { action in
            if action.isReply {
                return UNTextInputNotificationAction(
                    identifier: action.identifier,
                    title: action.title,
                    options: [],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: notification.replyPlaceholder
                )
            }
            return UNNotificationAction(identifier: action.identifier, title: action.title, options: [])
        }
        return UNNotificationCategory(
            identifier: notification.categoryIdentifier,
            actions: actions,
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Silent updates are posted as passive; keep them out of banners and sound
        // while the app is frontmost, as Notification Center does otherwise.
        if notification.request.content.interruptionLevel == .passive {
            completionHandler([.list])
        } else {
            completionHandler([.banner, .sound])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let key = response.notification.request.content.userInfo["key"] as? String
            ?? response.notification.request.identifier
        if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            onResponse?(key, "", nil, true)
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            // Tapping the banner does nothing on the phone.
        } else {
            let text = (response as? UNTextInputNotificationResponse)?.userText
            onResponse?(key, response.actionIdentifier, text, false)
        }
        completionHandler()
    }
}
