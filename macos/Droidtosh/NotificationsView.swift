import SwiftUI
import DroidsyncKit

/// Which of the phone's apps mirror their notifications to this Mac, with the notification
/// options beside the list.
struct NotificationsView: View {
    @ObservedObject var model: AppModel
    @State private var search = ""
    @State private var filter: Filter = .all

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All Apps"
        case mirrored = "Mirrored"
        case muted = "Muted"

        var id: String { rawValue }
    }

    private var notifications: NotificationsController { model.notifications }

    private var apps: [DSAppInfo] {
        let query = search.trimmingCharacters(in: .whitespaces)
        return notifications.installedApps.filter { app in
            let muted = notifications.mutedPackages.contains(app.packageName)
            switch filter {
            case .all: break
            case .mirrored: if muted { return false }
            case .muted: if !muted { return false }
            }
            guard !query.isEmpty else { return true }
            return app.label.localizedCaseInsensitiveContains(query)
                || app.packageName.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            appList
                .frame(minWidth: 320, maxWidth: .infinity)
            Divider()
            ScrollView {
                NotificationOptions(model: model)
                    .padding(18)
            }
            .frame(width: 300)
            .background(Brand.card.opacity(0.6))
        }
        .navigationTitle("Notifications")
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Show", selection: $filter) {
                    ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .disabled(notifications.installedApps.isEmpty)
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    notifications.requestAppsList()
                } label: {
                    Label("Refresh App List", systemImage: "arrow.clockwise")
                }
                .help("Ask the phone for its app list again")
            }
        }
    }

    @ViewBuilder
    private var appList: some View {
        if notifications.installedApps.isEmpty {
            // Over an empty list, as on the other screens, so the toolbar blends into the window.
            List {}
                .listStyle(.inset)
                .overlay {
                    ContentUnavailableView {
                        Label(
                            model.connection.connectedDevice == nil ? "No Phone Connected" : "No Apps Yet",
                            systemImage: "bell.slash"
                        )
                    } description: {
                        Text(
                            model.connection.connectedDevice == nil
                                ? "Connect your phone to choose which apps mirror their notifications."
                                : "Your phone hasn't reported its apps yet."
                        )
                    } actions: {
                        if model.connection.connectedDevice != nil {
                            Button("Refresh App List") { notifications.requestAppsList() }
                        }
                    }
                }
        } else {
            List {
                Section {
                    ForEach(apps, id: \.packageName) { app in
                        appRow(app)
                    }
                } header: {
                    Text(summary)
                } footer: {
                    Text("Muted apps stay quiet on this Mac; your phone still shows their notifications.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .listStyle(.inset)
            .overlay {
                if apps.isEmpty {
                    ContentUnavailableView.search(text: search)
                }
            }
            .searchable(text: $search, placement: .toolbar, prompt: "Search apps")
            .animation(.easeOut(duration: 0.2), value: filter)
        }
    }

    private var summary: String {
        let total = notifications.installedApps.count
        let muted = notifications.installedApps.filter { notifications.mutedPackages.contains($0.packageName) }.count
        return "\(total) apps, \(total - muted) mirrored, \(muted) muted"
    }

    private func appRow(_ app: DSAppInfo) -> some View {
        let muted = notifications.mutedPackages.contains(app.packageName)
        let name = app.label.isEmpty ? app.packageName : app.label
        return HStack(spacing: 10) {
            AppMonogram(name: name, seed: app.packageName)
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                Text(app.packageName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(muted ? "Muted" : "Mirrored")
                .font(.caption)
                .foregroundStyle(.secondary)
                .contentTransition(.opacity)
            // On = mirrored; off mutes the app (the mute list is what the phone stores).
            Toggle("Mirror notifications from \(name)", isOn: Binding(
                get: { !notifications.mutedPackages.contains(app.packageName) },
                set: { mirrored in
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                        notifications.setMuted(package: app.packageName, muted: !mirrored)
                    }
                }
            ))
            .labelsHidden()
            .toggleStyle(.switch)
            .controlSize(.small)
        }
        .padding(.vertical, 3)
        .contextMenu {
            Button(muted ? "Unmute" : "Mute") {
                notifications.setMuted(package: app.packageName, muted: !muted)
            }
            Button("Open on Phone") { notifications.openApp(package: app.packageName) }
        }
    }
}

/// Apps whose Mac versions can make mirrored notifications and calls redundant.
struct DedupApp: Identifiable {
    let id: String
    let label: String

    static let all: [DedupApp] = [
        DedupApp(id: "com.whatsapp", label: "WhatsApp"),
        DedupApp(id: "org.telegram.messenger", label: "Telegram"),
    ]
}

/// The notification options: banners on this Mac, the phone's relay, and desktop duplicates.
private struct NotificationOptions: View {
    @ObservedObject var model: AppModel

    private var notifications: NotificationsController { model.notifications }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            OptionGroup("Mirroring") {
                OptionRow("Banners on this Mac", detail: permissionText) {
                    Button("Open…") { SystemSettings.openNotifications() }
                        .buttonStyle(OutlineButtonStyle(compact: true))
                        .help("Open Notifications in System Settings")
                }
                OptionRow("Sending from the phone", detail: notifications.notificationsRelayEnabled ? "On" : "Off. Turn it on in the phone app.")
                OptionFootnote("Phone notifications appear as Mac banners. Reply, use their actions, or dismiss them on either side.")
            }
            OptionGroup("Pause") {
                PausePhoneNotificationsControl(notifications: notifications)
                OptionFootnote("While paused, phone notifications skip banners and sounds on this Mac and wait quietly in Notification Center. Nothing is lost or dismissed.")
            }
            OptionGroup("Verification codes") {
                Toggle(isOn: Binding(
                    get: { notifications.copyCodesAutomatically },
                    set: { notifications.setCopyCodesAutomatically($0) }
                )) {
                    Text("Copy codes automatically")
                        .fixedSize(horizontal: false, vertical: true)
                }
                .toggleStyle(.switch)
                .controlSize(.small)
                OptionFootnote("Banners with a one-time code get a Copy Code button. Copied codes stay off your phone and are cleared from the clipboard after a minute.")
            }
            OptionGroup("Desktop app duplicates") {
                Toggle(isOn: Binding(
                    get: { notifications.dedupEnabled },
                    set: { notifications.setDedupEnabled($0) }
                )) {
                    Text("Hide mirrored notifications while the Mac app is running")
                        .fixedSize(horizontal: false, vertical: true)
                }
                .toggleStyle(.switch)
                .controlSize(.small)
                ForEach(DedupApp.all) { app in
                    Toggle(isOn: Binding(
                        get: { !notifications.dedupDisabledPackages.contains(app.id) },
                        set: { notifications.setDedupDisabled(package: app.id, disabled: !$0) }
                    )) {
                        Text(app.label)
                    }
                    .toggleStyle(.checkbox)
                    .disabled(!notifications.dedupEnabled)
                    .padding(.leading, 4)
                }
                OptionFootnote("Avoids seeing the same message twice when the app is also open on this Mac.")
            }
            Button("Banner style in System Settings…") { SystemSettings.openNotifications() }
                .buttonStyle(.inkLink)
                .font(.callout)
        }
    }

    private var permissionText: String {
        switch notifications.notificationsAuthorized {
        case true?: return "Allowed"
        case false?: return "Blocked. Allow them in System Settings."
        case nil: return "Not determined"
        }
    }
}

// MARK: Option panels

/// A titled group in a page's options column.
struct OptionGroup<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(CardBackground(cornerRadius: 12))
        }
    }
}

/// A label and value (and an optional trailing control) in an option group.
struct OptionRow<Trailing: View>: View {
    let title: String
    let detail: String?
    @ViewBuilder var trailing: Trailing

    init(_ title: String, detail: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.detail = detail
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.callout.weight(.medium))
                if let detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 6)
            trailing
        }
        .accessibilityElement(children: .combine)
    }
}

extension OptionRow where Trailing == EmptyView {
    init(_ title: String, detail: String? = nil) {
        self.init(title, detail: detail) { EmptyView() }
    }
}

struct OptionFootnote: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A grey initial standing in for an Android app's icon, stable per package name.
struct AppMonogram: View {
    let name: String
    let seed: String
    var size: CGFloat = 28

    private static let palette = Brand.monograms

    var body: some View {
        let color = Self.palette[Int(seed.utf8.reduce(UInt32(5381)) { ($0 &* 33) &+ UInt32($1) } % UInt32(Self.palette.count))]
        Text(String(name.first(where: \.isLetter) ?? "?").uppercased())
            .font(.system(size: size * 0.46, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous).fill(color))
            .accessibilityHidden(true)
    }
}

/// The pause menu, or the paused status with a Resume button.
struct PausePhoneNotificationsControl: View {
    @ObservedObject var notifications: NotificationsController

    var body: some View {
        if let status = notifications.pauseStatusText() {
            OptionRow(status) {
                Button("Resume") { notifications.resume() }
                    .buttonStyle(OutlineButtonStyle(compact: true))
            }
        } else {
            OptionRow("Pause phone notifications") {
                PausePhoneNotificationsMenu(notifications: notifications)
            }
        }
    }
}

struct PausePhoneNotificationsMenu: View {
    @ObservedObject var notifications: NotificationsController

    var body: some View {
        Menu("Pause") {
            ForEach(NotificationPause.allCases, id: \.self) { duration in
                Button(duration.title) { notifications.pause(duration) }
            }
        }
        .menuIndicator(.visible)
        .fixedSize()
    }
}
