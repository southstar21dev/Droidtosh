import AppKit
import SwiftUI
import DroidsyncKit

/// The menu bar popover: the phone at a glance, quick actions, recent transfers and problems.
/// Files dropped anywhere on it are sent to the phone.
struct MenuBarView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openSettings) private var openSettings
    @State private var dropTargeted = false

    private var windows: WindowOpener { WindowOpener(openWindow: openWindow, openSettings: openSettings) }
    private var status: DroidtoshStatus { DroidtoshStatus(statusText: model.connection.statusText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
                .padding(.bottom, 4)
            if model.device.showsStatus {
                PhoneStatusChips(device: model.device)
                    .padding(.horizontal, PopoverMetrics.padding)
                    .padding(.vertical, 9)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .popoverSection()
            }
            quickActions
            if let text = FindPhoneText.status(model.device), model.device.showsFind {
                Label(text, systemImage: "speaker.wave.2")
                    .font(.caption)
                    .foregroundStyle(FindPhoneText.tint(model.device))
                    .padding(.horizontal, 4)
            }
            syncToggles
            pauseRow
            if !recentTransfers.isEmpty || model.files.phoneSupportsFiles {
                transfers
            }
            attention
            footer
                .padding(.top, 2)
        }
        .animation(.easeOut(duration: 0.2), value: AttentionItem.items(for: model).map(\.id))
        .padding(PopoverMetrics.padding)
        .frame(width: 340)
        .overlay { if dropTargeted { dropOverlay } }
        .sendFilesOnDrop(files: model.files, isTargeted: $dropTargeted)
        .onAppear { model.notifications.refreshNotificationStatus() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            TetherView(state: model.device.findCanStop ? .echo : status.tether, size: 24)
                .foregroundStyle(Brand.onInk)
                .frame(width: 34, height: 34)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Brand.ink))
            VStack(alignment: .leading, spacing: 2) {
                Text(model.connection.connectedDevice ?? "Droidtosh")
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 5) {
                    Text(statusLine)
                        .lineLimit(1)
                    if status == .offline || status == .connecting {
                        Button("Reconnect") { model.connection.reconnectNow() }
                            .buttonStyle(.link)
                    }
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
            return model.connection.statusHeadline
        }
    }

    // MARK: Quick actions

    private var quickActions: some View {
        HStack(spacing: 8) {
            PopoverActionButton(title: "Send File", symbol: "square.and.arrow.up") {
                model.files.pickAndSend()
            }
            .disabled(!model.files.phoneSupportsFiles)
            .help(model.files.phoneSupportsFiles ? "Choose files or folders to send" : "Connect your phone to send files")
            PopoverActionButton(
                title: model.device.findCanStop ? "Stop Ringing" : "Find Phone",
                symbol: FindPhoneText.symbol(model.device),
                tether: model.device.findCanStop ? .echo : nil
            ) {
                model.device.toggleFind()
            }
            .disabled(!model.device.showsFind)
            .help(model.device.showsFind ? model.device.findButtonTitle : "Find My Phone isn't available for this phone")
            PopoverActionButton(title: "Open Window", symbol: "macwindow") {
                windows.main()
            }
                .help("Open the Droidtosh window")
        }
    }

    // MARK: Sync

    private var syncToggles: some View {
        VStack(spacing: 0) {
            syncRow("Clipboard", symbol: "doc.on.clipboard", isOn: Binding(
                get: { model.clipboard.enabled },
                set: { model.clipboard.enabled = $0 }
            ))
            Divider().padding(.leading, 34)
            syncRow("Receive files", symbol: "tray.and.arrow.down", isOn: Binding(
                get: { model.files.receivingEnabled },
                set: { model.files.receivingEnabled = $0 }
            ))
        }
        .padding(.horizontal, PopoverMetrics.padding)
        .padding(.vertical, 4)
        .popoverSection()
    }

    private var pauseRow: some View {
        HStack(spacing: 10) {
            Image(systemName: model.notifications.isPaused ? "bell.slash" : "bell")
                .frame(width: 22)
                .foregroundStyle(.secondary)
            Text(model.notifications.pauseStatusText() ?? "Phone notifications")
                .font(.callout)
                .lineLimit(1)
            Spacer()
            if model.notifications.isPaused {
                Button("Resume") { model.notifications.resume() }
                    .buttonStyle(.borderless)
                    .font(.callout.weight(.medium))
            } else {
                PausePhoneNotificationsMenu(notifications: model.notifications)
                    .buttonStyle(.borderless)
                    .controlSize(.small)
            }
        }
        .padding(.horizontal, PopoverMetrics.padding)
        .padding(.vertical, 8)
        .popoverSection()
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.notifications.pausedUntil)
    }

    private func syncRow(_ title: String, symbol: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .frame(width: 22)
                .foregroundStyle(.secondary)
            Text(title)
                .font(.callout)
            Spacer()
            Toggle(title, isOn: isOn.animation(.spring(response: 0.3, dampingFraction: 0.7)))
                .toggleStyle(.switch)
                .labelsHidden()
                .controlSize(.mini)
        }
        .padding(.vertical, 6)
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
        .padding(PopoverMetrics.padding)
        .popoverSection()
    }

    private var dropOverlay: some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(Brand.ink, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.regularMaterial))
            .overlay {
                VStack(spacing: 8) {
                    TetherView(state: .streaming(toPhone: true), size: 40)
                    Text(model.files.phoneSupportsFiles ? "Drop to send to \(model.connection.connectedDevice ?? "your phone")" : "Connect your phone to send files")
                        .font(.headline)
                }
            }
            .padding(6)
            .allowsHitTesting(false)
    }

    // MARK: Problems

    /// One attention line instead of a stack of banners; further items wait behind a count.
    @ViewBuilder
    private var attention: some View {
        let items = AttentionItem.items(for: model)
        if let first = items.first {
            HStack(spacing: 10) {
                Image(systemName: first.symbol)
                    .font(.body.weight(.semibold))
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(first.title)
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    if let detail = first.detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 4)
                if items.count > 1 {
                    Menu {
                        ForEach(items.dropFirst()) { item in
                            Button(item.title) { item.perform(model: model, setup: windows.pairing) }
                        }
                    } label: {
                        Text("+\(items.count - 1)")
                            .font(.caption.weight(.semibold))
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                }
                if let actionTitle = first.actionTitle {
                    Button(actionTitle) { first.perform(model: model, setup: windows.pairing) }
                        .buttonStyle(InkButtonStyle(compact: true))
                }
            }
            .padding(10)
            .popoverSection()
            .transition(.opacity.combined(with: .move(edge: .bottom)))
            .accessibilityElement(children: .combine)
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 6) {
            Text("Droidtosh \(BuildConfig.version)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Button("Settings…") { windows.settings() }
                .buttonStyle(.borderless)
                .font(.caption)
                .help("Settings")
            Button("Quit") { NSApplication.shared.terminate(nil) }
                .controlSize(.small)
                .help("Quit Droidtosh")
        }
    }
}

/// Shared sizes, so every block in the popover lines up.
enum PopoverMetrics {
    static let padding: CGFloat = 12
    static let cornerRadius: CGFloat = 12
}

extension View {
    /// A block in the popover. The popover is already glass (macOS 26) or a material, so its
    /// blocks are a translucent tint of it rather than opaque cards or a second layer of glass.
    func popoverSection(tint: Color? = nil, highlight: Double = 0) -> some View {
        background(
            RoundedRectangle(cornerRadius: PopoverMetrics.cornerRadius, style: .continuous)
                .fill((tint ?? .primary).opacity((tint == nil ? 0.06 : 0.16) + highlight))
        )
    }
}

/// A popover quick action: an icon over a short title.
struct PopoverActionButton: View {
    let title: String
    let symbol: String
    /// Shown instead of the symbol while something is happening (Find My Phone ringing).
    var tether: TetherState?
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Group {
                    if let tether {
                        TetherView(state: tether, size: 20)
                    } else {
                        Image(systemName: symbol)
                            .font(.system(size: 15, weight: .medium))
                    }
                }
                .frame(height: 20)
                Text(title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(.primary)
            .opacity(isEnabled ? 1 : 0.4)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .contentShape(RoundedRectangle(cornerRadius: PopoverMetrics.cornerRadius, style: .continuous))
        }
        .buttonStyle(PopoverActionStyle(tint: nil, hovering: hovering && isEnabled))
        .onHover { hovering = $0 }
        .accessibilityLabel(title)
    }
}

private struct PopoverActionStyle: ButtonStyle {
    let tint: Color?
    let hovering: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .popoverSection(tint: tint, highlight: configuration.isPressed ? 0.08 : hovering ? 0.04 : 0)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: hovering)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}
