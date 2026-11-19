import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI
import DroidsyncKit

/// The guided setup: how the phone connects, the pairing code, then notifications. Shown as its
/// own window from the menu bar and as a sheet in the main window. Once a connection is set
/// up it opens on the pairing code.
struct SetupView: View {
    @ObservedObject var model: AppModel
    /// Closes the sheet; nil in the standalone window, which dismisses itself.
    var onDone: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var step: Step
    @State private var copied = false

    enum Step: Int, CaseIterable {
        case connection = 1
        case pair
        case notifications

        var title: String {
            switch self {
            case .connection: return "Connection"
            case .pair: return "Pair your phone"
            case .notifications: return "Allow notifications"
            }
        }
    }

    init(model: AppModel, onDone: (() -> Void)? = nil) {
        self.model = model
        self.onDone = onDone
        _step = State(initialValue: model.connectionConfigured ? .pair : .connection)
    }

    var body: some View {
        VStack(spacing: 0) {
            stepper
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
            Divider()
            Group {
                switch step {
                case .connection: connectionStep
                case .pair: pairStep
                case .notifications: notificationsStep
                }
            }
            .padding(28)
            .frame(maxWidth: .infinity, minHeight: 360, alignment: .topLeading)
            .id(step)
            .transition(.asymmetric(insertion: reduceMotion ? .opacity : .opacity.combined(with: .offset(x: 18)), removal: .opacity))
            Divider()
            footer
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
        }
        .frame(width: 720)
        .background(Brand.surface)
        .animation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.85), value: step)
    }

    private func close() {
        if let onDone { onDone() } else { dismiss() }
    }

    private func go(_ next: Step) {
        step = next
    }

    // MARK: Stepper

    private var stepper: some View {
        HStack(spacing: 10) {
            ForEach(Step.allCases, id: \.self) { item in
                if item != .connection {
                    Rectangle()
                        .fill(Color.primary.opacity(item.rawValue <= step.rawValue ? 0.6 : 0.15))
                        .frame(width: 28, height: 1)
                }
                Button {
                    if item == .connection || model.connectionConfigured { go(item) }
                } label: {
                    HStack(spacing: 7) {
                        ZStack {
                            Circle()
                                .fill(item.rawValue <= step.rawValue || done(item) ? Brand.ink : Color.primary.opacity(0.08))
                            if done(item) && item != step {
                                Image(systemName: "checkmark")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(Brand.onInk)
                            } else {
                                Text("\(item.rawValue)")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(item.rawValue <= step.rawValue ? Brand.onInk : .secondary)
                            }
                        }
                        .frame(width: 22, height: 22)
                        Text(item.title)
                            .font(.callout.weight(item == step ? .semibold : .regular))
                            .foregroundStyle(item == step ? .primary : .secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Step \(item.rawValue): \(item.title)\(done(item) ? ", done" : "")")
                .accessibilityAddTraits(item == step ? [.isSelected] : [])
            }
            Spacer(minLength: 0)
        }
    }

    private func done(_ item: Step) -> Bool {
        switch item {
        case .connection: return model.connectionConfigured
        case .pair: return model.pairing.isPaired || model.connection.phoneConnected
        case .notifications: return model.notifications.notificationsAuthorized == true
        }
    }

    // MARK: Step 1: connection

    private var connectionStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 4) {
                Text("How should your phone reach this Mac?")
                    .font(.title2.weight(.semibold))
                Text("You can change this later on the Connection page.")
                    .foregroundStyle(.secondary)
            }
            ConnectionModePicker(mode: Binding(
                get: { model.connection.connectionMode },
                set: { model.connection.connectionMode = $0 }
            ))
            if model.connection.connectionMode.usesRelay {
                VStack(alignment: .leading, spacing: 10) {
                    RelayFields(connection: model.connection)
                    HStack(spacing: 8) {
                        TetherView(state: relayCheck.state, size: 18)
                        Text(relayCheck.text)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    .animation(.easeOut(duration: 0.2), value: relayCheck.text)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            } else {
                HStack(alignment: .top, spacing: 10) {
                    TetherView(state: .seeking, size: 22)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("No relay needed")
                            .font(.callout.weight(.semibold))
                        Text("This Mac will listen on \(LocalNetworkAddress.primaryIPv4() ?? "your network") and accept only devices on your network. macOS may ask to allow local network access.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Brand.card))
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// A live check of the relay, read from the connection's status line.
    private var relayCheck: (state: TetherState, text: String) {
        guard model.relayConfigured else { return (.resting, "Enter your relay's address.") }
        let lower = model.connection.statusText.lowercased()
        let status = DroidtoshStatus(statusText: model.connection.statusText)
        if status == .connected || lower.contains("waiting for phone") {
            return (.linked, "Reachable. Its certificate will be pinned when you pair.")
        }
        if status == .offline { return (.resting, model.connection.statusText) }
        return (.handshake, "Checking the relay…")
    }

    // MARK: Step 2: pair

    private var pairStep: some View {
        HStack(alignment: .top, spacing: 28) {
            codeColumn
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Scan this code with your phone")
                        .font(.title2.weight(.semibold))
                    Text(introText)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(alignment: .leading, spacing: 10) {
                    numbered(1, "Install and open Droidtosh on your Android phone.")
                    numbered(2, "Choose to pair with a Mac.")
                    numbered(3, "Scan this code. The phone connects within a few seconds.")
                }
                waitingRow
                details
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var waitingRow: some View {
        let connected = model.connection.phoneConnected
        return HStack(spacing: 10) {
            TetherView(state: connected ? .linked : .seeking, size: 26)
            Text(connected ? "Connected to \(model.connection.connectedDevice ?? "your phone")" : "Waiting for your phone…")
                .font(.callout.weight(.medium))
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Brand.card))
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: connected)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var codeColumn: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.white)
                    .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
                if let uri = model.pairing.pairingURI, let image = Self.qrImage(from: uri) {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .padding(16)
                        .accessibilityLabel("Pairing QR code")
                        .overlay { ScanLine() }
                } else {
                    unavailable
                }
            }
            .frame(width: 236, height: 236)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
            if let uri = model.pairing.pairingURI {
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(uri, forType: .string)
                    // The link carries the pairing key; keep clipboard sync off it.
                    NSPasteboard.general.setString("", forType: SystemPasteboard.concealedType)
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                } label: {
                    Label(copied ? "Copied" : "Copy Pairing Link", systemImage: copied ? "checkmark" : "link")
                }
                .buttonStyle(OutlineButtonStyle(compact: true))
                .help("Copies a link you can open on the phone instead of scanning. It contains the pairing key.")
            }
        }
    }

    @ViewBuilder
    private var unavailable: some View {
        if !model.connectionConfigured {
            VStack(spacing: 10) {
                Image(systemName: "network.slash")
                    .font(.system(size: 34))
                    .foregroundStyle(.gray)
                Text("Choose a connection first")
                    .font(.headline)
                    .foregroundStyle(.black)
                Button("Back to Connection") { go(.connection) }
                    .controlSize(.small)
            }
            .padding()
        } else {
            VStack(spacing: 10) {
                TetherView(state: .handshake, size: 34)
                    .foregroundStyle(.black)
                Text(model.connection.connectionMode == .local ? "Preparing the pairing code…" : "Waiting for the relay…")
                    .font(.callout)
                    .foregroundStyle(.gray)
            }
        }
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 8) {
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow {
                    Text("This Mac").foregroundStyle(.secondary)
                    Text(macText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if model.connection.connectionMode.usesRelay {
                    GridRow {
                        Text("Relay").foregroundStyle(.secondary)
                        HStack(spacing: 6) {
                            Text(model.relayConfigured ? "\(model.connection.relayHost):\(model.connection.relayPortText)" : "Not configured")
                            Button("Change") { go(.connection) }
                                .buttonStyle(.inkLink)
                        }
                    }
                    GridRow {
                        Text("Fingerprint").foregroundStyle(.secondary)
                        Text(fingerprintText)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if model.connection.connectionMode.usesLocal {
                    GridRow {
                        Text("Local network").foregroundStyle(.secondary)
                        Text("\(LocalNetworkAddress.primaryIPv4() ?? "No network"):\(model.connection.localPortText)")
                    }
                }
            }
            .font(.callout)
            Label(
                model.connection.connectionMode == .local
                    ? "The phone pins this Mac's certificate, so it only ever talks to this Mac."
                    : "The phone pins this fingerprint, so it only ever talks to your relay.",
                systemImage: "lock"
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func numbered(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(number)")
                .font(.caption.weight(.bold))
                .foregroundStyle(Brand.onInk)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Brand.ink))
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private var macText: String {
        let name = model.connection.macName
        if name.isEmpty { return "Unknown" }
        return model.connection.macModel.isEmpty ? name : "\(name), \(model.connection.macModel)"
    }

    private var introText: String {
        switch model.connection.connectionMode {
        case .relay: return "Connect your Android phone to this Mac through your relay."
        case .local: return "Connect your Android phone to this Mac over your local network."
        case .both: return "Connect your Android phone to this Mac through your relay, or the local network when the relay can't be reached."
        }
    }

    /// The pinned fingerprint in groups of four, or why it's missing.
    private var fingerprintText: String {
        guard let fingerprint = model.connection.pinnedRelayFingerprint else {
            return "Learned when the relay first connects"
        }
        var groups: [String] = []
        var rest = Substring(fingerprint.uppercased())
        while !rest.isEmpty {
            groups.append(String(rest.prefix(4)))
            rest = rest.dropFirst(4)
        }
        return stride(from: 0, to: groups.count, by: 4)
            .map { groups[$0..<min($0 + 4, groups.count)].joined(separator: " ") }
            .joined(separator: "\n")
    }

    // MARK: Step 3: notifications

    private var notificationsStep: some View {
        HStack(alignment: .top, spacing: 28) {
            TetherView(state: .mirroring, size: 72)
                .foregroundStyle(Brand.onInk)
                .frame(width: 120, height: 120)
                .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Brand.ink))
            VStack(alignment: .leading, spacing: 12) {
                Text("Allow notifications")
                    .font(.title2.weight(.semibold))
                Text("Lets your phone's notifications and calls appear on this Mac as banners you can reply to.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Image(systemName: model.notifications.notificationsAuthorized == true ? "checkmark.circle.fill" : "bell.badge")
                    Text(permissionText)
                        .font(.callout.weight(.medium))
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Brand.card))
                Button("Open Notification Settings") { SystemSettings.openNotifications() }
                    .buttonStyle(OutlineButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { model.notifications.refreshNotificationStatus() }
    }

    private var permissionText: String {
        switch model.notifications.notificationsAuthorized {
        case true?: return "Allowed on this Mac"
        case false?: return "Blocked. Allow Droidtosh in System Settings."
        case nil: return "macOS asks the first time a notification arrives"
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack(spacing: 10) {
            if step != .connection {
                Button("Back") { go(Step(rawValue: step.rawValue - 1) ?? .connection) }
                    .buttonStyle(OutlineButtonStyle(compact: true))
            } else {
                Button("Relay setup guide") {
                    if let url = URL(string: "https://github.com/southstar21dev/Droidtosh/blob/master/relay/README.md") {
                        NSWorkspace.shared.open(url)
                    }
                }
                .buttonStyle(.inkLink)
                .font(.callout)
                .help("Opens how to host a relay, on GitHub")
            }
            Spacer()
            switch step {
            case .connection:
                Button("Continue") { go(.pair) }
                    .buttonStyle(InkButtonStyle(compact: true))
                    .disabled(!model.connectionConfigured)
                    .keyboardShortcut(.defaultAction)
            case .pair:
                Button(done(.pair) ? "Close" : "Skip for Now") { close() }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                Button("Continue") { go(.notifications) }
                    .buttonStyle(InkButtonStyle(compact: true))
                    .disabled(!done(.pair))
                    .keyboardShortcut(.defaultAction)
            case .notifications:
                Button("Done") { close() }
                    .buttonStyle(InkButtonStyle(compact: true))
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    static func qrImage(from string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else {
            return nil
        }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

/// A line that sweeps the pairing code while it waits to be scanned.
private struct ScanLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: reduceMotion)) { timeline in
            GeometryReader { proxy in
                let period = 3.0
                let t = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
                let travel = (1 - cos(2 * .pi * t)) / 2
                Rectangle()
                    .fill(.black.opacity(0.5))
                    .frame(height: 2)
                    .shadow(color: .black.opacity(0.3), radius: 6)
                    .offset(y: 12 + (proxy.size.height - 26) * travel)
            }
        }
        .opacity(reduceMotion ? 0 : 1)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
