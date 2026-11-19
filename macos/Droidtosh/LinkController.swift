import AppKit
import UserNotifications
import DroidsyncKit

/// Links and phone numbers between the Mac and the phone (PROTOCOL-v2.md section 10): the
/// "open_url" message in both directions, the "Send to Phone" services, and `tel:` links.
///
/// A link from the phone is never opened by itself: it becomes a banner, and a click opens it in the
/// default browser.
@MainActor
final class LinkController {
    private static let maxPending = 20

    private let connection: ConnectionController
    private let clipboard: ClipboardController
    private let files: FilesController
    private let calls: CallsController
    private let center = UNUserNotificationCenter.current()
    private var pending: [(id: String, url: URL)] = []
    private var delegate: LinkNotificationDelegate?
    private let launchedAt = Date()
    private var hasHadPhone = false
    private var pendingDial: PendingDial?
    private var pendingDialTask: Task<Void, Never>?

    init(connection: ConnectionController, clipboard: ClipboardController, files: FilesController, calls: CallsController) {
        self.connection = connection
        self.clipboard = clipboard
        self.files = files
        self.calls = calls
    }

    /// Puts a delegate in front of `presenter` that handles clicks on link banners and passes
    /// everything else on, so the presenter stays as it is.
    func installNotificationDelegate(forwardingTo presenter: UNUserNotificationCenterDelegate) {
        let delegate = LinkNotificationDelegate(next: presenter) { [weak self] key in
            Task { @MainActor in self?.handleNotificationResponse(key: key) }
        }
        self.delegate = delegate
        center.delegate = delegate
    }

    // MARK: From the phone

    func handle(_ envelope: DSEnvelope) {
        guard case .openURL(let message)? = envelope.payload else { return }
        // The Mac only ever opens web links; a phone number from the phone has nothing to open here.
        guard case .web(let raw, let host)? = LinkPolicy.validate(message.url, allowTel: false),
              let url = URL(string: raw)
        else {
            DebugLog.log("link: rejected an open_url from the phone")
            return
        }
        let id = UUID().uuidString
        pending.append((id, url))
        if pending.count > Self.maxPending { pending.removeFirst(pending.count - Self.maxPending) }
        notify(title: "Open link from phone: \(host)", body: Self.preview(raw), identifier: "link:\(id)")
    }

    /// A click on a link banner (`link:<id>`).
    func handleNotificationResponse(key: String) {
        let id = String(key.dropFirst("link:".count))
        guard let index = pending.firstIndex(where: { $0.id == id }) else { return }
        let url = pending.remove(at: index).url
        NSWorkspace.shared.open(url)
    }

    // MARK: To the phone (Services)

    /// "Send to Phone" for selected text: a single link goes as a tap-to-open link (it works with
    /// clipboard sync off, since it never touches the phone's clipboard); other text goes to the
    /// phone's clipboard, which needs the clipboard switch on.
    func sendToPhone(text: String) {
        if let url = LinkPolicy.singleWebURL(in: text), sendLink(url) { return }
        if !clipboard.enabled {
            notify(title: "Clipboard sync is off", body: "Turn it on in Droidtosh to send text to your phone.", identifier: "link.notice")
        } else if !clipboard.sendText(text) {
            notify(title: "Couldn't send the text", body: "It is empty or too large.", identifier: "link.notice")
        }
    }

    /// "Send File to Phone" through the usual file transfer.
    func sendToPhone(files urls: [URL]) {
        guard !urls.isEmpty else { return }
        files.send(urls: urls)
    }

    /// Sends `url` as a link; false when the connected phone is too old for links.
    @discardableResult
    func sendLink(_ url: String) -> Bool {
        guard LinkPolicy.validate(url, allowTel: false) != nil else { return false }
        if connection.phoneConnected, !connection.negotiatedCapabilities.contains(ProtocolV2.Capability.openUrl) {
            return false
        }
        var message = DSOpenUrl()
        message.url = url
        connection.send(protocolEnvelope(.openURL(message)))
        return true
    }

    // MARK: tel: links

    /// A `tel:` link opened with Droidtosh: asks first (a web page must not start a call by
    /// itself), on the SIM used last, then uses the usual dial request.
    func handleTel(_ url: URL) {
        // A link that launched the app arrives before the phone has had time to connect: hold the
        // latest one and run it on authentication. A link clicked while the app has been running
        // and the phone is away still gets the notice at once.
        let now = Date()
        if !connection.phoneConnected,
           PendingDial.shouldWait(now: now, launchedAt: launchedAt, hasHadPhone: hasHadPhone) {
            let dial = PendingDial(link: url.absoluteString, deadline: now.addingTimeInterval(PendingDial.grace))
            pendingDial = dial
            pendingDialTask?.cancel()
            pendingDialTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(PendingDial.grace * 1_000_000_000))
                guard !Task.isCancelled, let self, self.pendingDial == dial else { return }
                self.pendingDial = nil
                self.dialOrNotify(url)
            }
            return
        }
        dialOrNotify(url)
    }

    /// The phone has authenticated: runs a dial that was waiting for it.
    func phoneAuthenticated() {
        hasHadPhone = true
        pendingDialTask?.cancel()
        pendingDialTask = nil
        guard let dial = pendingDial else { return }
        pendingDial = nil
        if let link = dial.usable(now: Date()), let url = URL(string: link) {
            dialOrNotify(url)
        }
    }

    private func dialOrNotify(_ url: URL) {
        guard let text = DialAccountPicker.telLink(from: url.absoluteString),
              case .tel(_, let number)? = LinkPolicy.validate(text, allowTel: true)
        else {
            DebugLog.log("link: ignored a tel: link that is not a phone number")
            return
        }
        guard connection.phoneConnected else {
            notify(title: "Can't call \(number)", body: "Your phone isn't connected.", identifier: "link.notice")
            return
        }
        let accounts = calls.phoneAccounts
        let preselected = DialAccountPicker.defaultAccount(accounts: accounts.map(\.id), lastUsed: calls.lastDialAccount)
        guard let choice = TelDialPrompt.ask(number: number, accounts: accounts, preselected: preselected) else { return }
        calls.dial(number: number, phoneAccountId: choice)
    }

    // MARK: Helpers

    private func notify(title: String, body: String, identifier: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = ["key": identifier]
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
            if let error { DebugLog.log("link: center.add ERROR id=\(identifier) error=\(error)") }
        }
    }

    private static func preview(_ url: String) -> String {
        url.count > 120 ? String(url.prefix(120)) + "..." : url
    }
}

/// Sits in front of the notification presenter as the center's delegate: a click on a link banner is
/// handled here, everything else goes to the presenter unchanged.
private final class LinkNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    private let next: UNUserNotificationCenterDelegate
    private let onLinkClick: (String) -> Void

    init(next: UNUserNotificationCenterDelegate, onLinkClick: @escaping (String) -> Void) {
        self.next = next
        self.onLinkClick = onLinkClick
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if let forward = next.userNotificationCenter(_:willPresent:withCompletionHandler:) {
            forward(center, notification, completionHandler)
        } else {
            completionHandler([.banner, .sound])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let key = response.notification.request.content.userInfo["key"] as? String ?? ""
        if key.hasPrefix("link:") {
            if response.actionIdentifier == UNNotificationDefaultActionIdentifier { onLinkClick(key) }
            completionHandler()
        } else if let forward = next.userNotificationCenter(_:didReceive:withCompletionHandler:) {
            forward(center, response, completionHandler)
        } else {
            completionHandler()
        }
    }
}

/// The confirmation shown for a `tel:` link, with a SIM choice when the phone has several.
@MainActor
enum TelDialPrompt {
    /// The chosen account id ("" when the phone reported none), or nil when the user cancelled.
    static func ask(number: String, accounts: [DSPhoneAccount], preselected: String?) -> String? {
        let alert = NSAlert()
        alert.messageText = "Call \(number) from your phone?"
        alert.informativeText = "Droidtosh will place the call on your phone."
        alert.addButton(withTitle: "Call")
        alert.addButton(withTitle: "Cancel")
        var popup: NSPopUpButton?
        if accounts.count > 1 {
            let button = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 220, height: 26), pullsDown: false)
            for account in accounts {
                button.addItem(withTitle: account.label.isEmpty ? account.id : account.label)
                button.lastItem?.representedObject = account.id
            }
            if let preselected, let index = accounts.firstIndex(where: { $0.id == preselected }) {
                button.selectItem(at: index)
            }
            alert.accessoryView = button
            popup = button
        }
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        if let popup { return popup.selectedItem?.representedObject as? String ?? "" }
        return accounts.first?.id ?? ""
    }
}

/// The "Send to Phone" and "Send File to Phone" services (declared as NSServices in Info.plist).
/// Their selectors are named by the NSMessage entries there.
final class ServicesProvider: NSObject {
    private let link: LinkController

    @MainActor
    init(link: LinkController) {
        self.link = link
    }

    @objc func sendToPhone(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) ?? ""
        guard !text.isEmpty else {
            error.pointee = "There is no text to send." as NSString
            return
        }
        MainActor.assumeIsolated { link.sendToPhone(text: text) }
    }

    @objc func sendFileToPhone(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = (pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        guard !urls.isEmpty else {
            error.pointee = "There is no file to send." as NSString
            return
        }
        MainActor.assumeIsolated { link.sendToPhone(files: urls) }
    }
}
