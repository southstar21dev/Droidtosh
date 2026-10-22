import AppKit
import SwiftUI
import DroidsyncKit

/// The menu bar popover: the phone at a glance, quick actions, recent transfers and problems.
/// Files dropped anywhere on it are sent to the phone.
struct MenuBarView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings
    @State private var dropTargeted = false

    private var windows: WindowOpener { WindowOpener(openWindow: openWindow, openSettings: openSettings) }
    private var status: DroidtoshStatus { DroidtoshStatus(statusText: model.connection.statusText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if model.device.showsStatus {
                PhoneStatusChips(device: model.device)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(CardBackground(cornerRadius: 10))
            }
            quickActions
            if let text = FindPhoneText.status(model.device), model.device.showsFind {
                Label(text, systemImage: "speaker.wave.2")
                    .font(.caption)
                    .foregroundStyle(FindPhoneText.tint(model.device))
                    .padding(.horizontal, 4)
            }
            if !recentTransfers.isEmpty || model.files.phoneSupportsFiles {
                transfers
            }
            banners
            Divider()
            footer
        }
        .padding(14)
        .frame(width: 340)
        .overlay { if dropTargeted { dropOverlay } }
        .sendFilesOnDrop(files: model.files, isTargeted: $dropTargeted)
        .onAppear { model.notifications.refreshNotificationStatus() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            BrandBadge(size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.connection.connectedDevice ?? "Droidtosh")
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 5) {
                    Circle().fill(status.color).frame(width: 7, height: 7)
                    Text(statusLine)
                        .lineLimit(1)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Menu {
                Button("Open Droidtosh") { windows.main() }
                Button("Pair Phone…") { windows.pairing() }
                Button("Settings…") { windows.settings() }
                Divider()
                Button("Quit Droidtosh") { NSApplication.shared.terminate(nil) }
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 22, height: 22)
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.borderless)
            .fixedSize()
            .accessibilityLabel("More")
        }
    }

    private var statusLine: String {
        switch status {
        case .connected:
            let mac = model.connection.macName.isEmpty ? "this Mac" : model.connection.macName
            return model.connection.sessionPath == .local ? "Connected to \(mac) via local network" : "Connected to \(mac)"
        case .notPaired:
            return model.connectionConfigured ? "Not paired" : "Relay not configured"
        default:
            return model.connection.statusText
        }
    }

    // MARK: Quick actions

    private var quickActions: some View {
        GlassGroup(spacing: 8) {
            HStack(spacing: 8) {
                PopoverActionButton(title: "Send File", symbol: "square.and.arrow.up") {
                    model.files.pickAndSend()
                }
                .disabled(!model.files.phoneSupportsFiles)
                .help(model.files.phoneSupportsFiles ? "Choose files or folders to send" : "Connect your phone to send files")
                PopoverActionButton(
                    title: model.device.findCanStop ? "Stop Ringing" : "Find Phone",
                    symbol: FindPhoneText.symbol(model.device),
                    tint: model.device.findCanStop ? .red : nil
                ) {
                    model.device.toggleFind()
                }
                .disabled(!model.device.showsFind)
                .help(model.device.showsFind ? model.device.findButtonTitle : "Find My Phone isn't available for this phone")
                PopoverActionButton(title: "Open App", symbol: "macwindow") {
                    windows.main()
                }
                .help("Open the Droidtosh window")
            }
        }
    }

    // MARK: Transfers

    /// Active transfers first, then the latest finished ones; three at most.
    private var recentTransfers: [FileTransferSnapshot] {
        let active = model.files.activeTransfers
        let finished = model.files.transfers.filter { $0.phase.isFinished }
        return Array((active + finished).prefix(3))
    }

    private var transfers: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Transfers")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                if model.files.phoneSupportsFiles {
                    Text("Drop files here to send")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if recentTransfers.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "tray")
                        .foregroundStyle(.tertiary)
                    Text("No recent transfers")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
            } else {
                VStack(spacing: 8) {
                    ForEach(recentTransfers) { transfer in
                        FileTransferRow(transfer: transfer, files: model.files, compact: true)
                    }
                }
            }
        }
        .padding(10)
        .background(CardBackground(cornerRadius: 10))
    }

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.regularMaterial))
            .overlay {
                VStack(spacing: 8) {
                    Image(systemName: "arrow.down.doc.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(Color.accentColor)
                    Text(model.files.phoneSupportsFiles ? "Drop to send to \(model.connection.connectedDevice ?? "your phone")" : "Connect your phone to send files")
                        .font(.headline)
                }
            }
            .padding(6)
            .allowsHitTesting(false)
    }

    // MARK: Problems

    @ViewBuilder
    private var banners: some View {
        if !model.connectionConfigured {
            Banner(.warning, symbol: "network.slash", title: "Relay not configured", message: "Set a relay address in Settings, or choose Local network, to pair.") {
                Button("Open Settings…") { windows.settings() }
            }
        } else if !model.pairing.isPaired && !model.connection.phoneConnected {
            Banner(.info, symbol: "qrcode", title: "Pair your phone", message: "Scan the pairing code with Droidtosh on Android.") {
                Button("Show Pairing Code") { windows.pairing() }
            }
        }
        if case .available(let offer) = model.updates.state {
            Banner(.info, symbol: "arrow.down.circle.fill", title: "Update available", message: "Droidtosh \(offer.version) is ready to install.") {
                Button("Install and Relaunch") { model.updates.install() }
            }
        }
        if model.notifications.notificationsAuthorized == false {
            Banner(.warning, symbol: "bell.slash.fill", title: "Notifications blocked", message: "Enable them for Droidtosh in System Settings.") {
                Button("Open Notification Settings") { SystemSettings.openNotifications() }
            }
        }
        if !model.notifications.notificationsRelayEnabled {
            Banner(.warning, symbol: "exclamationmark.triangle.fill", title: "Notification relay is off", message: "Turn it on in the Droidtosh app on your phone.")
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 6) {
            Text("Droidtosh \(BuildConfig.version)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Button {
                windows.settings()
            } label: {
                Image(systemName: "gearshape")
            }
            .buttonStyle(.borderless)
            .help("Settings")
            .accessibilityLabel("Settings")
            Button("Quit") { NSApplication.shared.terminate(nil) }
                .controlSize(.small)
                .help("Quit Droidtosh")
        }
    }
}

/// A popover quick action: an icon over a short title, on glass (macOS 26) or a quiet fill.
struct PopoverActionButton: View {
    let title: String
    let symbol: String
    var tint: Color?
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: .medium))
                    .frame(height: 20)
                    .foregroundStyle(iconStyle)
                Text(title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(isEnabled ? .primary : .tertiary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .modifier(PopoverActionBackground(tint: tint, hovering: hovering && isEnabled))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityLabel(title)
    }

    private var iconStyle: AnyShapeStyle {
        guard isEnabled else { return AnyShapeStyle(.tertiary) }
        return AnyShapeStyle(tint ?? .accentColor)
    }
}

private struct PopoverActionBackground: ViewModifier {
    let tint: Color?
    let hovering: Bool

    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content.glassEffect(
                tint.map { Glass.regular.tint($0.opacity(0.25)).interactive() } ?? .regular.interactive(),
                in: .rect(cornerRadius: 12)
            )
        } else {
            content.background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill((tint ?? Color.primary).opacity(hovering ? 0.12 : 0.06))
            )
        }
    }
}
