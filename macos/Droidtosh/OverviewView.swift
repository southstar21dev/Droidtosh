import AppKit
import SwiftUI
import DroidsyncKit

/// The main window's home: the phone at a glance (or setup steps before pairing), problems,
/// phone status, Find My Phone and quick actions.
struct OverviewView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openSettings) private var openSettings
    @Environment(\.showPairing) private var showPairing
    @Environment(\.selectSection) private var selectSection

    private var needsSetup: Bool {
        !model.connectionConfigured || !(model.pairing.isPaired || model.connection.phoneConnected)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if needsSetup {
                    SetupHero(model: model)
                } else {
                    DeviceHero(model: model)
                }
                if !warnings.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(warnings) { $0 }
                    }
                }
                if needsSetup && !model.device.showsStatus && !model.device.showsFind {
                    mirroringCard
                } else {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .top, spacing: 16) {
                            VStack(spacing: 16) { leftColumn }
                                .frame(minWidth: 300)
                            VStack(spacing: 16) { rightColumn }
                                .frame(minWidth: 300)
                        }
                        VStack(spacing: 16) {
                            leftColumn
                            rightColumn
                        }
                    }
                }
                quickActions
            }
            .padding(24)
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Overview")
        .navigationSubtitle(model.connection.connectedDevice ?? "")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showPairing()
                } label: {
                    Label("Pair Phone", systemImage: "qrcode")
                }
                .help("Show the pairing code")
            }
        }
    }

    @ViewBuilder
    private var leftColumn: some View {
        PhoneDetailsCard(device: model.device)
    }

    @ViewBuilder
    private var rightColumn: some View {
        FindPhoneCard(device: model.device)
        mirroringCard
    }

    // MARK: Problems

    private var warnings: [OverviewWarning] {
        var items: [OverviewWarning] = []
        if !model.connectionConfigured && !needsSetup {
            items.append(OverviewWarning(id: "relay", symbol: "network.slash", title: "No relay configured", message: "Set a relay address in Settings, or choose Local network, to pair.", action: ("Open Settings…", openSettings.callAsFunction)))
        }
        if model.notifications.notificationsAuthorized == false {
            items.append(OverviewWarning(id: "blocked", symbol: "bell.slash.fill", title: "Notifications are blocked", message: "Enable them for Droidtosh in System Settings to see your phone's notifications.", action: ("Open Notification Settings", SystemSettings.openNotifications)))
        }
        if !model.notifications.notificationsRelayEnabled {
            items.append(OverviewWarning(id: "relay-off", symbol: "exclamationmark.triangle.fill", title: "Notification relay is off", message: "Turn it on in the Droidtosh app on your phone.", action: nil))
        }
        return items
    }

    // MARK: Quick actions

    private var quickActions: some View {
        Card {
            CardHeader("Quick Actions", symbol: "bolt.fill")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                QuickActionTile(title: "Send File…", symbol: "square.and.arrow.up", color: .blue) {
                    model.files.pickAndSend()
                }
                .disabled(!model.files.phoneSupportsFiles)
                QuickActionTile(title: "Pairing Code", symbol: "qrcode", color: Brand.lagoon) {
                    showPairing()
                }
                QuickActionTile(title: "Refresh Apps", symbol: "arrow.clockwise", color: .purple) {
                    model.notifications.requestAppsList()
                }
                QuickActionTile(title: "Call Log", symbol: "phone.fill", color: .green) {
                    selectSection(.calls)
                }
                QuickActionTile(title: "Notification Settings", symbol: "bell.badge.fill", color: .red) {
                    SystemSettings.openNotifications()
                }
                QuickActionTile(title: "Settings…", symbol: "gearshape.fill", color: .gray) {
                    openSettings()
                }
            }
        }
    }

    // MARK: Mirroring

    private var mirroringCard: some View {
        Card {
            CardHeader("Mirroring", symbol: "arrow.left.arrow.right") {
                Button("Settings…") { openSettings() }
                    .controlSize(.small)
            }
            VStack(spacing: 0) {
                mirrorRow("Notifications from phone", symbol: "bell.fill", color: .red, on: model.notifications.notificationsRelayEnabled, detail: model.notifications.notificationsRelayEnabled ? "On" : "Off on phone")
                Divider().padding(.leading, 36)
                mirrorRow("Notification banners on this Mac", symbol: "macwindow.badge.plus", color: .orange, on: model.notifications.notificationsAuthorized, detail: authorizationText)
                Divider().padding(.leading, 36)
                mirrorRow("Clipboard sync", symbol: "doc.on.clipboard.fill", color: .blue, on: model.clipboard.enabled, detail: model.clipboard.enabled ? "On" : "Off")
                Divider().padding(.leading, 36)
                mirrorRow("Receive files", symbol: "tray.and.arrow.down.fill", color: .teal, on: model.files.receivingEnabled, detail: model.files.receivingEnabled ? "Saved to Downloads" : "Off")
            }
        }
    }

    private var authorizationText: String {
        switch model.notifications.notificationsAuthorized {
        case true?: return "Allowed"
        case false?: return "Blocked"
        case nil: return "Not determined"
        }
    }

    /// `on` nil means unknown (e.g. notification permission not asked yet).
    private func mirrorRow(_ title: String, symbol: String, color: Color, on: Bool?, detail: String) -> some View {
        HStack(spacing: 10) {
            IconTile(symbol: symbol, color: color, size: 24)
            Text(title)
            Spacer()
            Text(detail)
                .foregroundStyle(on == false ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
            Image(systemName: on == nil ? "questionmark.circle.fill" : on! ? "checkmark.circle.fill" : "minus.circle.fill")
                .foregroundStyle(on == nil ? Color.secondary : on! ? Color.green : Color.orange)
                .accessibilityHidden(true)
        }
        .font(.callout)
        .padding(.vertical, 7)
        .accessibilityElement(children: .combine)
    }
}

private struct OverviewWarning: View, Identifiable {
    let id: String
    let symbol: String
    let title: String
    let message: String
    let action: (String, () -> Void)?

    var body: some View {
        Banner(.warning, symbol: symbol, title: title, message: message) {
            if let action {
                Button(action.0, action: action.1)
            }
        }
    }
}

/// A square-ish button with a coloured icon over its title, like Control Center tiles.
struct QuickActionTile: View {
    let title: String
    let symbol: String
    var color: Color = .accentColor
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                IconTile(symbol: symbol, color: isEnabled ? color : .gray, size: 28)
                Text(title)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(isEnabled ? .primary : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .padding(9)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.primary.opacity(hovering && isEnabled ? 0.08 : 0.04))
            )
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityLabel(title)
    }
}

// MARK: Heroes

/// The paired phone on the brand gradient: name, state, and battery, network and sound.
private struct DeviceHero: View {
    @ObservedObject var model: AppModel

    var body: some View {
        let status = DroidtoshStatus(statusText: model.connection.statusText)
        HStack(alignment: .center, spacing: 18) {
            Image(systemName: "iphone.gen3")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.white)
                .frame(width: 64, height: 64)
                .background(Circle().fill(.white.opacity(0.16)))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Text(model.connection.connectedDevice ?? "Your phone")
                        .font(.title2.weight(.semibold))
                        .lineLimit(1)
                    StatusBadge(status: status, onGradient: true)
                }
                Text(subtitle(status))
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.85))
                    .lineLimit(2)
                if model.device.showsStatus {
                    PhoneStatusChips(device: model.device, onGradient: true)
                        .padding(.top, 4)
                }
            }
            .foregroundStyle(.white)
            Spacer(minLength: 0)
        }
        .padding(22)
        .frame(maxWidth: .infinity, minHeight: 130, alignment: .leading)
        .background(HeroBackground())
        .accessibilityElement(children: .contain)
    }

    private func subtitle(_ status: DroidtoshStatus) -> String {
        let mac = model.connection.macName.isEmpty ? "this Mac" : model.connection.macName
        switch status {
        case .connected: return "Connected to \(mac) through \(model.connection.relayHost)"
        default: return model.connection.statusText
        }
    }
}

/// The brand gradient with a large faint glyph: the only gradient surface in the window.
struct HeroBackground: View {
    var cornerRadius: CGFloat = 16

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Brand.gradient)
            .overlay(alignment: .trailing) {
                Brand.glyph
                    .resizable()
                    .renderingMode(.template)
                    .scaledToFit()
                    .foregroundStyle(.white.opacity(0.1))
                    .frame(width: 190)
                    .offset(x: 30, y: 10)
                    .accessibilityHidden(true)
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .shadow(color: Brand.indigo.opacity(0.18), radius: 10, y: 4)
    }
}

/// Onboarding: what's left before the phone can connect.
private struct SetupHero: View {
    @ObservedObject var model: AppModel
    @Environment(\.openSettings) private var openSettings
    @Environment(\.showPairing) private var showPairing

    private var setupConnectionDetail: String {
        switch model.connection.connectionMode {
        case .local: return "The phone connects directly over your Wi-Fi; no relay needed."
        case .relay, .both: return model.relayConfigured ? model.connection.relayHost : "Enter your relay's address in Settings."
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                BrandBadge(size: 56)
                    .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Set up Droidtosh")
                        .font(.title2.weight(.semibold))
                    Text("Pair your Android phone to get its notifications, calls, clipboard and files on this Mac.")
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.85))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(.white)
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HeroBackground(cornerRadius: 0))

            VStack(spacing: 0) {
                step(1, title: model.connection.connectionMode == .local ? "Use the local network" : "Connect to a relay", detail: setupConnectionDetail, done: model.connectionConfigured) {
                    Button("Open Settings…") { openSettings() }
                }
                Divider().padding(.leading, 52)
                step(2, title: "Scan the pairing code", detail: "Open Droidtosh on your phone and scan the code shown on this Mac.", done: model.pairing.isPaired) {
                    Button("Show Pairing Code") { showPairing() }
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.connectionConfigured)
                }
                Divider().padding(.leading, 52)
                step(3, title: "Allow notifications", detail: "Lets your phone's notifications and calls appear on this Mac.", done: model.notifications.notificationsAuthorized == true) {
                    Button("Notification Settings") { SystemSettings.openNotifications() }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(Color(nsColor: .quaternarySystemFill))
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
    }

    private func step<Action: View>(_ number: Int, title: String, detail: String, done: Bool, @ViewBuilder action: () -> Action) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(done ? Color.green : Color.accentColor.opacity(0.15))
                if done {
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                } else {
                    Text("\(number)")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                }
            }
            .frame(width: 26, height: 26)
            .padding(.leading, 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.callout.weight(.semibold))
                Text(detail).font(.callout).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if !done {
                action().controlSize(.regular)
            }
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Step \(number): \(title)\(done ? ", done" : "")")
    }
}
