import AppKit
import ServiceManagement
import SwiftUI
import DroidsyncKit

/// The app's Settings window: General and Updates. Notification, call, file and connection
/// options live on their pages in the main window.
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
        .frame(width: 520)
    }
}

enum SettingsTab: String, CaseIterable, Identifiable {
    case general
    case updates

    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return "General"
        case .updates: return "Updates"
        }
    }

    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .updates: return "arrow.down.circle"
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
            case .updates: UpdateSettings(updates: model.updates)
            }
        }
        .formStyle(.grouped)
        .scrollDisabled(true)
        .fixedSize(horizontal: false, vertical: true)
        .frame(width: 520)
    }
}

// MARK: General

private struct GeneralSettings: View {
    @ObservedObject var model: AppModel
    @AppStorage("launch-at-login") private var launchAtLogin = false
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    BrandBadge(size: 48)
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
                Text("It lives in the menu bar and keeps your phone connected in the background.")
                    .settingsFooter()
            }
            Section {
                Toggle("Show phone battery in menu bar", isOn: Binding(
                    get: { model.device.showBatteryInMenuBar },
                    set: { model.device.showBatteryInMenuBar = $0 }
                ))
                Toggle("Low battery alerts", isOn: Binding(
                    get: { model.device.lowBatteryAlertsEnabled },
                    set: { model.device.lowBatteryAlertsEnabled = $0 }
                ))
            } header: {
                Text("Phone")
            } footer: {
                Text("Alerts at 20% and again at 10% while the phone isn't charging. Needs a phone app that reports its status.")
                    .settingsFooter()
            }
            Section {
                Toggle("Sync clipboard with phone", isOn: Binding(
                    get: { model.clipboard.enabled },
                    set: { model.clipboard.enabled = $0 }
                ))
                if model.connection.phoneConnected {
                    LabeledContent("Images", value: model.clipboard.phoneMetered ? "Ask first, the phone is on a metered network" : "Sent automatically")
                }
            } header: {
                Text("Clipboard")
            } footer: {
                Text("Text and images you copy on one device can be pasted on the other. Copies from password managers are never sent. While the phone is on cellular, copied images wait for you to confirm.")
                    .settingsFooter()
            }
            Section {
                Text("Looking for notifications, calls, files or the relay? Their options now live on those pages in the main window.")
                    .settingsFooter()
                Button("Open Droidtosh") {
                    openWindow(id: "main")
                    WindowActivation.present()
                }
            }
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

// MARK: Updates

private struct UpdateSettings: View {
    @ObservedObject var updates: UpdatesController

    var body: some View {
        Form {
            Section {
                LabeledContent("Version", value: BuildConfig.version)
                HStack {
                    statusLine
                    Spacer()
                    switch updates.state {
                    case .available:
                        Button("Install and Relaunch") { updates.install() }
                            .buttonStyle(InkButtonStyle(compact: true))
                    case .downloading, .installing, .checking:
                        ProgressView().controlSize(.small)
                    case .idle, .upToDate, .failed:
                        Button("Check Now") { updates.check() }
                    }
                }
                Toggle("Receive beta versions", isOn: $updates.includeBeta)
            } footer: {
                Text("Droidtosh checks for a new release every few hours and installs it only when you click Install. Beta versions get new features first and may be less stable.")
                    .settingsFooter()
            }
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

private extension View {
    func settingsFooter() -> some View {
        font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}
