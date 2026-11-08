import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI
import DroidsyncKit

/// The pairing code with the steps around it, this Mac, and the relay it pins. Shown as a
/// sheet in the main window and as its own window from the menu bar.
struct PairingView: View {
    @ObservedObject var model: AppModel
    /// Closes the sheet; nil in the standalone window, which dismisses itself.
    var onDone: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openSettings) private var openSettings
    @AppStorage("settings-tab") private var settingsTab: SettingsTab = .general
    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 28) {
                codeColumn
                infoColumn
            }
            .padding(28)
            Divider()
            HStack {
                let status = DroidtoshStatus(statusText: model.connection.statusText)
                StatusBadge(status: status)
                if model.connection.statusText != status.title {
                    Text(model.connection.statusText)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button("Done") { close() }
                    .keyboardShortcut(.defaultAction)
                    .controlSize(.large)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
        }
        .frame(width: 640)
    }

    private func close() {
        if let onDone { onDone() } else { dismiss() }
    }

    // MARK: Code

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
                    .foregroundStyle(.secondary)
                Text("Set up a relay first")
                    .font(.headline)
                    .foregroundStyle(.black)
                Button("Connection Settings…") {
                    settingsTab = .connection
                    WindowActivation.present()
                    openSettings()
                }
                .controlSize(.small)
            }
            .padding()
        } else {
            VStack(spacing: 10) {
                ProgressView()
                Text(model.connection.connectionMode == .local ? "Preparing the pairing code…" : "Waiting for the relay…")
                    .font(.callout)
                    .foregroundStyle(.gray)
            }
        }
    }

    // MARK: Info

    private var infoColumn: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Pair Your Phone")
                    .font(.title2.weight(.semibold))
                Text(introText)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(alignment: .leading, spacing: 10) {
                step(1, "Install and open Droidtosh on your Android phone.")
                step(2, "Choose to pair with a Mac.")
                step(3, "Scan this code. The phone connects within a few seconds.")
            }
            Divider()
            Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 8) {
                GridRow {
                    Text("This Mac").foregroundStyle(.secondary)
                    Text(macText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if model.connection.connectionMode.usesRelay {
                    GridRow {
                        Text("Relay").foregroundStyle(.secondary)
                        Text(model.relayConfigured ? "\(model.connection.relayHost):\(model.connection.relayPortText)" : "Not configured")
                    }
                    GridRow {
                        Text("Relay fingerprint").foregroundStyle(.secondary)
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
            Text(model.connection.connectionMode == .local
                 ? "The phone pins this Mac's certificate, so it only ever talks to this Mac."
                 : "The phone pins this fingerprint, so it only ever talks to your relay.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(number)")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Color.accentColor))
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private var macText: String {
        let name = model.connection.macName
        if name.isEmpty { return "—" }
        return model.connection.macModel.isEmpty ? name : "\(name) — \(model.connection.macModel)"
    }

    /// The pinned fingerprint in groups of four, or why it's missing.
    private var introText: String {
        switch model.connection.connectionMode {
        case .relay: return "Connect your Android phone to this Mac through your relay."
        case .local: return "Connect your Android phone to this Mac over your local network."
        case .both: return "Connect your Android phone to this Mac through your relay, or the local network when the relay can't be reached."
        }
    }

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
