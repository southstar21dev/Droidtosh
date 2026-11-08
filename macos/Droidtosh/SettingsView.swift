import AppKit
import ServiceManagement
import SwiftUI
import DroidsyncKit

/// The app's Settings window: one tab per area, each a grouped form.
struct SettingsView: View {
    @ObservedObject var model: AppModel
    @AppStorage("settings-tab") private var tab: SettingsTab = .general

    var body: some View {
        TabView(selection: $tab) {
            ForEach(SettingsTab.allCases) { tab in
                SettingsPane(model: model, tab: tab)
                    .tabItem { Label(tab.title, systemImage: tab.symbol) }
                    .tag(tab)
            }
        }
        .frame(width: 560)
    }
}

enum SettingsTab: String, CaseIterable, Identifiable {
    case general
    case connection
    case notifications
    case calls
    case clipboard
    case files
    case phone

    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return "General"
        case .connection: return "Connection"
        case .notifications: return "Notifications"
        case .calls: return "Calls"
        case .clipboard: return "Clipboard"
        case .files: return "Files"
        case .phone: return "Phone"
        }
    }

    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .connection: return "network"
        case .notifications: return "bell.badge"
        case .calls: return "phone"
        case .clipboard: return "doc.on.clipboard"
        case .files: return "folder"
        case .phone: return "iphone.gen3"
        }
    }
}

/// One Settings tab's form.
struct SettingsPane: View {
    @ObservedObject var model: AppModel
    let tab: SettingsTab

    var body: some View {
        Group {
            switch tab {
            case .general: GeneralSettings(model: model)
            case .connection: ConnectionSettings(model: model)
            case .notifications: NotificationSettings(model: model)
            case .calls: CallSettings(model: model)
            case .clipboard: ClipboardSettings(model: model)
            case .files: FileSettings(model: model)
            case .phone: PhoneSettings(model: model)
            }
        }
        .formStyle(.grouped)
        .scrollDisabled(true)
        .fixedSize(horizontal: false, vertical: true)
        .frame(width: 560)
    }
}

/// Apps whose Mac versions can make mirrored notifications and calls redundant.
private struct DedupApp: Identifiable {
    let id: String
    let label: String

    static let all: [DedupApp] = [
        DedupApp(id: "com.whatsapp", label: "WhatsApp"),
        DedupApp(id: "org.telegram.messenger", label: "Telegram"),
    ]
}

// MARK: General

private struct GeneralSettings: View {
    @ObservedObject var model: AppModel
    @AppStorage("launch-at-login") private var launchAtLogin = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    BrandBadge(size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Droidtosh")
                            .font(.title3.weight(.semibold))
                        Text("Version \(BuildConfig.version)")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    StatusBadge(status: DroidtoshStatus(statusText: model.connection.statusText))
                }
                .padding(.vertical, 4)
            }
            Section {
                Toggle("Open Droidtosh at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, enabled in
                        applyLaunchAtLogin(enabled)
                    }
            } footer: {
                Text("Droidtosh lives in the menu bar and keeps your phone connected in the background.")
                    .settingsFooter()
            }
            UpdatesSection(updates: model.updates)
        }
    }

    private func applyLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            launchAtLogin = SMAppService.mainApp.status == .enabled
        }
    }
}

private struct UpdatesSection: View {
    @ObservedObject var updates: UpdatesController

    var body: some View {
        Section {
            LabeledContent("Version", value: BuildConfig.version)
            HStack {
                statusLine
                Spacer()
                switch updates.state {
                case .available:
                    Button("Install and Relaunch") { updates.install() }
                        .buttonStyle(.borderedProminent)
                case .downloading, .installing, .checking:
                    ProgressView().controlSize(.small)
                case .idle, .upToDate, .failed:
                    Button("Check Now") { updates.check() }
                }
            }
            Toggle("Receive beta versions", isOn: $updates.includeBeta)
        } header: {
            Text("Updates")
        } footer: {
            Text("Droidtosh checks for a new release every few hours and installs it only when you click Install. Beta versions get new features first and may be less stable.")
                .settingsFooter()
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        switch updates.state {
        case .idle:
            Text("Not checked yet").foregroundStyle(.secondary)
        case .checking:
            Text("Checking for updates…").foregroundStyle(.secondary)
        case .upToDate:
            Text("Droidtosh is up to date").foregroundStyle(.secondary)
        case .available(let offer):
            Text("Version \(offer.version)\(offer.prerelease ? " (beta)" : "") is available")
        case .downloading(let offer):
            Text("Downloading \(offer.version)…").foregroundStyle(.secondary)
        case .installing(let offer):
            Text("Installing \(offer.version)…").foregroundStyle(.secondary)
        case .failed(let message):
            Text(message).foregroundStyle(.secondary)
        }
    }
}

// MARK: Connection & Pairing

private struct ConnectionSettings: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings
    @State private var showUnpairConfirmation = false

    var body: some View {
        Form {
            Section {
                LabeledContent("Status") {
                    HStack(spacing: 6) {
                        StatusBadge(status: DroidtoshStatus(statusText: model.connectionConfigured ? model.connection.statusText : "Not configured"))
                    }
                }
                if model.connectionConfigured, DroidtoshStatus(statusText: model.connection.statusText) != .connected {
                    Text(model.connection.statusText)
                        .settingsFooter()
                }
                LabeledContent("Paired phone", value: model.connection.connectedDevice ?? (model.pairing.isPaired ? "Paired" : "None"))
                HStack {
                    Button("Show Pairing Code…") {
                        openWindow(id: "pairing")
                        WindowActivation.present()
                    }
                    Spacer()
                    Button("Unpair…", role: .destructive) {
                        showUnpairConfirmation = true
                    }
                    .disabled(!model.pairing.isPaired && model.connection.connectedDevice == nil)
                }
                .confirmationDialog("Unpair this phone?", isPresented: $showUnpairConfirmation) {
                    Button("Unpair", role: .destructive) { model.unpair() }
                } message: {
                    Text("The phone will need to scan a new pairing code.")
                }
            } header: {
                Text("Pairing")
            }

            Section {
                Picker("Connect through", selection: Binding(
                    get: { model.connection.connectionMode },
                    set: { model.connection.connectionMode = $0 }
                )) {
                    Text("Relay").tag(ConnectionMode.relay)
                    Text("Local network").tag(ConnectionMode.local)
                    Text("Relay, local network as fallback").tag(ConnectionMode.both)
                }
                if model.connection.connectionMode.usesLocal {
                    TextField("Local port", text: Binding(
                        get: { model.connection.localPortText },
                        set: { model.connection.localPortText = $0 }
                    ), prompt: Text(String(LocalListenerOptions.defaultPort)))
                    LabeledContent("Local network", value: localStatusText)
                }
                if let path = model.connection.sessionPath {
                    LabeledContent("Phone connected via", value: path == .local ? "Local network" : "Relay")
                }
            } header: {
                Text("Connection")
            } footer: {
                Text("The local network works without a relay while the phone is on the same Wi-Fi as this Mac. A new pairing code carries this choice to the phone; after pairing, change it on the phone too.")
                    .settingsFooter()
            }

            if model.connection.connectionMode.usesRelay {
            Section {
                TextField("Host", text: Binding(
                    get: { model.connection.relayHost },
                    set: { model.connection.relayHost = $0 }
                ), prompt: Text("relay.example.com"))
                TextField("Port", text: Binding(
                    get: { model.connection.relayPortText },
                    set: { model.connection.relayPortText = $0 }
                ), prompt: Text("7443"))
                LabeledContent("Fingerprint") {
                    TextField("Fingerprint", text: Binding(
                        get: { model.connection.relayFingerprintText },
                        set: { model.connection.relayFingerprintText = $0 }
                    ), prompt: Text("Learned on first connect"))
                        .labelsHidden()
                        .multilineTextAlignment(.trailing)
                        .font(.system(.callout, design: .monospaced))
                }
            } header: {
                Text("Relay")
            } footer: {
                Text("Compare the fingerprint with the “relay fingerprint” line in the relay’s log. Clear it to trust the relay’s current certificate again.")
                    .settingsFooter()
            }
            }
        }
    }

    private var localStatusText: String {
        switch model.connection.localState {
        case .listening(let port): return "Listening on \(LocalNetworkAddress.primaryIPv4() ?? "this Mac"):\(port)"
        case .failed(let message): return "Unavailable — \(message)"
        case .off: return "Off"
        }
    }
}

// MARK: Notifications

private struct NotificationSettings: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Form {
            Section {
                LabeledContent("Banners on this Mac") {
                    HStack(spacing: 8) {
                        Text(permissionText)
                            .foregroundStyle(model.notifications.notificationsAuthorized == false ? .orange : .secondary)
                        Button("Open System Settings…") { SystemSettings.openNotifications() }
                            .controlSize(.small)
                    }
                }
                LabeledContent("Relay on phone", value: model.notifications.notificationsRelayEnabled ? "On" : "Off — turn it on in the phone app")
            } header: {
                Text("Mirroring")
            } footer: {
                Text("Choose which apps mirror their notifications in the Notifications section of the Droidtosh window.")
                    .settingsFooter()
            }

            Section {
                Toggle(
                    "Hide mirrored notifications while the Mac app is running",
                    isOn: Binding(
                        get: { model.notifications.dedupEnabled },
                        set: { model.notifications.setDedupEnabled($0) }
                    )
                )
                ForEach(DedupApp.all) { app in
                    Toggle(isOn: Binding(
                        get: { !model.notifications.dedupDisabledPackages.contains(app.id) },
                        set: { model.notifications.setDedupDisabled(package: app.id, disabled: !$0) }
                    )) {
                        Text(app.label)
                    }
                    .disabled(!model.notifications.dedupEnabled)
                    .padding(.leading, 18)
                }
            } header: {
                Text("Desktop App Duplicates")
            } footer: {
                Text("Avoids seeing the same message twice when the app is also open on this Mac.")
                    .settingsFooter()
            }
        }
    }

    private var permissionText: String {
        switch model.notifications.notificationsAuthorized {
        case true?: return "Allowed"
        case false?: return "Blocked"
        case nil: return "Not determined"
        }
    }
}

// MARK: Calls

private struct CallSettings: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Form {
            Section {
                Toggle(
                    "Hide mirrored calls while the Mac app is running",
                    isOn: Binding(
                        get: { model.calls.dedupCallsEnabled },
                        set: { model.calls.setDedupCallsEnabled($0) }
                    )
                )
                ForEach(DedupApp.all) { app in
                    Toggle(isOn: Binding(
                        get: { !model.calls.dedupCallsDisabledPackages.contains(app.id) },
                        set: { model.calls.setDedupCallsDisabled(package: app.id, disabled: !$0) }
                    )) {
                        Text("\(app.label) calls")
                    }
                    .disabled(!model.calls.dedupCallsEnabled)
                    .padding(.leading, 18)
                }
            } header: {
                Text("Desktop App Duplicates")
            } footer: {
                Text("When WhatsApp or Telegram is open on this Mac, its own call window rings instead of Droidtosh's.")
                    .settingsFooter()
            }
            Section {
                LabeledContent("SIMs", value: simText)
            } header: {
                Text("Phone")
            } footer: {
                Text("Incoming calls show a panel with Accept, Decline and Mute. Place calls from the Calls section.")
                    .settingsFooter()
            }
        }
    }

    private var simText: String {
        let accounts = model.calls.phoneAccounts
        if accounts.isEmpty { return "—" }
        return accounts.map { $0.label.isEmpty ? $0.id : $0.label }.joined(separator: ", ")
    }
}

// MARK: Clipboard

private struct ClipboardSettings: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Form {
            Section {
                Toggle("Sync clipboard with phone", isOn: Binding(
                    get: { model.clipboard.enabled },
                    set: { model.clipboard.enabled = $0 }
                ))
                if model.connection.phoneConnected {
                    LabeledContent("Images", value: model.clipboard.phoneMetered ? "Ask first — phone is on a metered network" : "Sent automatically")
                }
            } footer: {
                Text("Text and images you copy on one device can be pasted on the other. Copies from password managers are never sent. While the phone is on cellular, copied images wait for you to confirm.")
                    .settingsFooter()
            }
        }
    }
}

// MARK: Files

private struct FileSettings: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Form {
            Section {
                Toggle("Receive files from phone", isOn: Binding(
                    get: { model.files.receivingEnabled },
                    set: { model.files.receivingEnabled = $0 }
                ))
                LabeledContent("Save received files to") {
                    HStack(spacing: 8) {
                        Label {
                            Text(model.files.downloadsDirectory.lastPathComponent)
                        } icon: {
                            Image(nsImage: NSWorkspace.shared.icon(forFile: model.files.downloadsDirectory.path))
                                .resizable()
                                .frame(width: 16, height: 16)
                        }
                        .help(model.files.downloadsDirectory.path(percentEncoded: false))
                        Button("Show in Finder") { model.files.showDownloads() }
                            .controlSize(.small)
                    }
                }
            } footer: {
                Text("To send files, drop them on the menu bar icon, the popover or the Files section. Folders are sent as a zip archive, up to 2 GB.")
                    .settingsFooter()
            }
        }
    }
}

// MARK: Phone

private struct PhoneSettings: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Form {
            Section {
                Toggle("Show phone battery in menu bar", isOn: Binding(
                    get: { model.device.showBatteryInMenuBar },
                    set: { model.device.showBatteryInMenuBar = $0 }
                ))
                Toggle("Low battery alerts", isOn: Binding(
                    get: { model.device.lowBatteryAlertsEnabled },
                    set: { model.device.lowBatteryAlertsEnabled = $0 }
                ))
            } footer: {
                Text("Alerts at 20% and again at 10% while the phone isn’t charging. Needs a phone app that reports its status.")
                    .settingsFooter()
            }
        }
    }
}

private extension View {
    func settingsFooter() -> some View {
        font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}
