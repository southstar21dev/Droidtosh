import AppKit
import SwiftUI
import DroidsyncKit

/// Everything about the link in one place: how the phone connects, the relay and its
/// fingerprint, the local listener, the paired phone and diagnostics.
struct ConnectionView: View {
    @ObservedObject var model: AppModel
    @Environment(\.showPairing) private var showPairing
    @State private var showUnpairConfirmation = false

    private var connection: ConnectionController { model.connection }
    private var status: DroidtoshStatus {
        DroidtoshStatus(statusText: model.connectionConfigured ? connection.statusText : "Not configured")
    }

    var body: some View {
        ScrollView {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 18) {
                    VStack(alignment: .leading, spacing: 18) { mainColumn }
                        .frame(minWidth: 380)
                    VStack(alignment: .leading, spacing: 18) { sideColumn }
                        .frame(width: 300)
                }
                VStack(alignment: .leading, spacing: 18) {
                    mainColumn
                    sideColumn
                }
            }
            .padding(24)
            .frame(maxWidth: 980)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Connection")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showPairing()
                } label: {
                    Label("Show Pairing Code", systemImage: "qrcode")
                }
                .help("Show the pairing code")
            }
        }
    }

    // MARK: Main column

    @ViewBuilder
    private var mainColumn: some View {
        statusCard
            .entrance()
        OptionGroup("How the phone connects") {
            ConnectionModePicker(mode: Binding(
                get: { connection.connectionMode },
                set: { connection.connectionMode = $0 }
            ))
            OptionFootnote("The local network works without a relay while the phone is on the same Wi-Fi as this Mac. A new pairing code carries this choice to the phone; after pairing, change it on the phone too.")
        }
        .entrance(0.06)
        if connection.connectionMode.usesRelay {
            OptionGroup("Relay") {
                RelayFields(connection: connection)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Fingerprint")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        TextField("Fingerprint", text: Binding(
                            get: { connection.relayFingerprintText },
                            set: { connection.relayFingerprintText = $0 }
                        ), prompt: Text("Learned on first connect"))
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.callout, design: .monospaced))
                        Button("Clear") { connection.relayFingerprintText = "" }
                            .buttonStyle(OutlineButtonStyle(compact: true))
                            .disabled(connection.relayFingerprintText.isEmpty)
                            .help("Trust the relay's current certificate again")
                    }
                }
                OptionFootnote("Compare the fingerprint with the \"relay fingerprint\" line in the relay's log. Clear it to trust the relay's current certificate again.")
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
            .entrance(0.1)
        }
        if connection.connectionMode.usesLocal {
            OptionGroup("Local network") {
                HStack {
                    Text("Port")
                    Spacer()
                    TextField("Local port", text: Binding(
                        get: { connection.localPortText },
                        set: { connection.localPortText = $0 }
                    ), prompt: Text(String(LocalListenerOptions.defaultPort)))
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 110)
                    .labelsHidden()
                }
                OptionRow("Listener", detail: LocalStatus.text(connection.localState))
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
            .entrance(0.12)
        }
    }

    private var statusCard: some View {
        HStack(spacing: 14) {
            TetherView(state: status.tether, size: 30)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Brand.surface))
            VStack(alignment: .leading, spacing: 2) {
                Text(statusTitle)
                    .font(.headline)
                Text(statusDetail)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Brand.hero))
        .animation(.easeOut(duration: 0.2), value: status)
    }

    private var statusTitle: String {
        switch status {
        case .connected:
            return connection.sessionPath == .local ? "Connected over the local network" : "Connected through your relay"
        case .notPaired:
            return model.connectionConfigured ? "Not paired" : "Not set up yet"
        case .connecting:
            return "Connecting"
        case .offline:
            return "Offline"
        }
    }

    private var statusDetail: String {
        switch status {
        case .connected:
            return "Encrypted end to end. A relay only ever sees bytes."
        default:
            return model.connectionConfigured ? connection.statusText : "Choose how the phone connects below, then pair it."
        }
    }

    // MARK: Side column

    @ViewBuilder
    private var sideColumn: some View {
        OptionGroup("Paired phone") {
            HStack(spacing: 10) {
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 18))
                    .frame(width: 32, height: 32)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Brand.tile))
                VStack(alignment: .leading, spacing: 1) {
                    Text(connection.connectedDevice ?? (model.pairing.isPaired ? "Paired" : "None"))
                        .font(.callout.weight(.semibold))
                    if let path = connection.sessionPath {
                        Text(path == .local ? "Connected via local network" : "Connected via relay")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            HStack(spacing: 8) {
                Button(model.pairing.isPaired ? "Pair Again…" : "Pair…") { showPairing() }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                Button("Unpair…", role: .destructive) { showUnpairConfirmation = true }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                    .disabled(!model.pairing.isPaired && connection.connectedDevice == nil)
            }
            .confirmationDialog("Unpair this phone?", isPresented: $showUnpairConfirmation) {
                Button("Unpair", role: .destructive) { model.unpair() }
            } message: {
                Text("The phone will need to scan a new pairing code.")
            }
        }
        .entrance(0.08)
        DiagnosticsSummary(diagnostics: model.diagnostics)
            .entrance(0.14)
    }
}

/// The relay host and port.
struct RelayFields: View {
    @ObservedObject var connection: ConnectionController

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Address")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("Host", text: Binding(
                    get: { connection.relayHost },
                    set: { connection.relayHost = $0 }
                ), prompt: Text("relay.example.com"))
                .textFieldStyle(.roundedBorder)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Port")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("Port", text: Binding(
                    get: { connection.relayPortText },
                    set: { connection.relayPortText = $0 }
                ), prompt: Text("7443"))
                .textFieldStyle(.roundedBorder)
                .frame(width: 80)
            }
        }
    }
}

enum LocalStatus {
    static func text(_ state: RelayTransport.LocalState) -> String {
        switch state {
        case .listening(let port): return "Listening on \(LocalNetworkAddress.primaryIPv4() ?? "this Mac"), port \(port)"
        case .failed(let message): return "Unavailable: \(message)"
        case .off: return "Off"
        }
    }
}

/// Three cards for the connection mode, the chosen one lifted.
struct ConnectionModePicker: View {
    @Binding var mode: ConnectionMode
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private struct Option: Identifiable {
        let id: ConnectionMode
        let title: String
        let detail: String
        let tag: String?
    }

    private let options: [Option] = [
        Option(id: .local, title: "Local network", detail: "Phone and Mac on the same Wi-Fi. Nothing to host.", tag: nil),
        Option(id: .relay, title: "Relay", detail: "Works anywhere, through a small relay you host yourself.", tag: nil),
        Option(id: .both, title: "Relay, Wi-Fi as fallback", detail: "Uses the relay, and the local network when the relay is down.", tag: "Recommended"),
    ]

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ForEach(options) { option in
                card(option)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Connection mode")
    }

    private func card(_ option: Option) -> some View {
        let selected = mode == option.id
        return Button {
            withAnimation(reduceMotion ? nil : .spring(response: 0.32, dampingFraction: 0.78)) { mode = option.id }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(selected ? .primary : .tertiary)
                    Spacer()
                    if let tag = option.tag {
                        Text(tag)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(Color.primary.opacity(0.08)))
                    }
                }
                Text(option.title)
                    .font(.callout.weight(.semibold))
                Text(option.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Brand.surface))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? Brand.ink : Color.primary.opacity(0.15), lineWidth: selected ? 2 : 1)
            )
            .shadow(color: .black.opacity(selected ? 0.12 : 0), radius: selected ? 10 : 0, y: selected ? 4 : 0)
            .scaleEffect(selected || reduceMotion ? 1.0 : 0.985)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityLabel("\(option.title). \(option.detail)")
    }
}

/// The short diagnostics card: state, round trip and the actions; the full report opens in a sheet.
private struct DiagnosticsSummary: View {
    @ObservedObject var diagnostics: DiagnosticsController
    @State private var copied = false
    @State private var showsDetails = false

    private var snapshot: DiagnosticsSnapshot { diagnostics.snapshot }

    var body: some View {
        OptionGroup("Diagnostics") {
            VStack(spacing: 6) {
                row("State", snapshot.state.capitalized)
                row("Round trip", snapshot.rtt.last.map(DiagnosticsReport.ms) ?? "Not measured")
                row("Connected for", snapshot.sessionStartedAt.map {
                    DiagnosticsReport.duration(snapshot.capturedAt.timeIntervalSince($0))
                } ?? "Not connected")
                row("Last error", snapshot.lastError?.message ?? "None")
            }
            HStack(spacing: 8) {
                Button("Details…") { showsDetails = true }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                Button(copied ? "Copied" : "Copy Report") {
                    diagnostics.copyReport()
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                }
                .buttonStyle(OutlineButtonStyle(compact: true))
                .help("Copy a redacted diagnostics report")
            }
            HStack(spacing: 8) {
                Button("Reconnect Now") { diagnostics.reconnectNow() }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                    .help("Drop the relay connection and connect again")
                Button("Export Logs…") { diagnostics.exportLogs() }
                    .buttonStyle(OutlineButtonStyle(compact: true))
                    .help("Save the report and the debug log as a text file")
            }
            Label("Reports leave out keys, room tokens and pairing codes.", systemImage: "lock")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .onAppear { diagnostics.startUpdating() }
        .onDisappear { diagnostics.stopUpdating() }
        .sheet(isPresented: $showsDetails) {
            NavigationStack {
                DiagnosticsView(diagnostics: diagnostics)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showsDetails = false }
                        }
                    }
            }
            .frame(minWidth: 620, minHeight: 560)
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .monospacedDigit()
        }
        .font(.callout)
    }
}
