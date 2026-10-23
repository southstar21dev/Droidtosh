import SwiftUI
import DroidsyncKit

/// Which of the phone's apps mirror their notifications to this Mac.
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
        Group {
            if notifications.installedApps.isEmpty {
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
            } else {
                List {
                    if hasStatusBanner {
                        Section { statusBanner }
                            .listRowSeparator(.hidden)
                    }
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
            }
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

    private var summary: String {
        let total = notifications.installedApps.count
        let muted = notifications.installedApps.filter { notifications.mutedPackages.contains($0.packageName) }.count
        return "\(total) apps · \(total - muted) mirrored · \(muted) muted"
    }

    private var hasStatusBanner: Bool {
        notifications.notificationsAuthorized == false || !notifications.notificationsRelayEnabled
    }

    @ViewBuilder
    private var statusBanner: some View {
        if notifications.notificationsAuthorized == false {
            Banner(.warning, symbol: "bell.slash.fill", title: "Notifications are blocked on this Mac", message: "Allow them for Droidtosh in System Settings.") {
                Button("Open Notification Settings") { SystemSettings.openNotifications() }
            }
        } else if !notifications.notificationsRelayEnabled {
            Banner(.warning, symbol: "exclamationmark.triangle.fill", title: "Notification relay is off", message: "Turn it on in the Droidtosh app on your phone.")
        }
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
            if muted {
                Text("Muted")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            // On = mirrored; off mutes the app (the mute list is what the phone stores).
            Toggle("Mirror notifications from \(name)", isOn: Binding(
                get: { !notifications.mutedPackages.contains(app.packageName) },
                set: { notifications.setMuted(package: app.packageName, muted: !$0) }
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

/// A coloured initial standing in for an Android app's icon, stable per package name.
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
