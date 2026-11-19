import AppKit
import SwiftUI
import DroidsyncKit

/// The main window's home: the phone (or setup before pairing), what syncs, recent calls and
/// transfers. Problems collapse into one attention line in the toolbar.
struct HomeView: View {
    @ObservedObject var model: AppModel
    @Environment(\.showPairing) private var showPairing
    @Environment(\.selectSection) private var selectSection

    private var needsSetup: Bool {
        !model.connectionConfigured || !(model.pairing.isPaired || model.connection.phoneConnected)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Group {
                    if needsSetup {
                        SetupCard(model: model)
                    } else {
                        PhoneCard(model: model)
                    }
                }
                .entrance()
                syncSection
                    .entrance(0.07)
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 12) {
                        RecentCallsCard(model: model)
                        RecentTransfersCard(model: model)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    VStack(spacing: 12) {
                        RecentCallsCard(model: model)
                        RecentTransfersCard(model: model)
                    }
                }
                .entrance(0.14)
            }
            .padding(20)
            .frame(maxWidth: 1100)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Home")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                AttentionLine(items: AttentionItem.items(for: model)) { item in
                    item.perform(model: model, setup: showPairing)
                }
                .animation(.easeOut(duration: 0.2), value: AttentionItem.items(for: model).map(\.id))
            }
        }
        .onAppear {
            #if DEBUG
            if SnapshotRenderer.isActive { return }
            #endif
            if model.callLog.entries.isEmpty && model.connection.phoneConnected {
                model.callLog.request(reset: true)
            }
        }
    }

    // MARK: Sync

    private var syncSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Sync")
                .font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), spacing: 12, alignment: .top)], spacing: 12) {
                SyncCard(
                    title: "Notifications",
                    detail: notificationsDetail,
                    icon: .symbol("bell"),
                    isOn: nil,
                    open: { selectSection(.notifications) }
                )
                SyncCard(
                    title: "Calls",
                    detail: callsDetail,
                    icon: model.calls.activeCall?.phase == .ringing ? .tether(.ringing) : .symbol("phone"),
                    isOn: nil,
                    open: { selectSection(.calls) }
                )
                SyncCard(
                    title: "Clipboard",
                    detail: model.clipboard.enabled ? "Text and images, both ways" : "Off. Copies stay on each device.",
                    icon: model.clipboard.enabled ? .tether(.mirroring) : .symbol("doc.on.clipboard"),
                    isOn: Binding(get: { model.clipboard.enabled }, set: { model.clipboard.enabled = $0 }),
                    open: nil
                )
                SyncCard(
                    title: "Receive files",
                    detail: model.files.receivingEnabled ? "Saved to \(model.files.downloadsDirectory.lastPathComponent)" : "Off. The phone can't send files.",
                    icon: .symbol("tray.and.arrow.down"),
                    isOn: Binding(get: { model.files.receivingEnabled }, set: { model.files.receivingEnabled = $0 }),
                    open: { selectSection(.files) }
                )
            }
        }
    }

    private var notificationsDetail: String {
        let notifications = model.notifications
        if notifications.notificationsAuthorized == false { return "Blocked on this Mac" }
        if !notifications.notificationsRelayEnabled { return "Off in the phone app" }
        let total = notifications.installedApps.count
        guard total > 0 else { return "Phone notifications as Mac banners" }
        let muted = notifications.installedApps.filter { notifications.mutedPackages.contains($0.packageName) }.count
        return "\(total - muted) apps mirrored, \(muted) muted"
    }

    private var callsDetail: String {
        if let call = model.calls.activeCall {
            return call.phase == .ringing ? "Incoming call" : "In a call"
        }
        let sims = model.calls.phoneAccounts.count
        return sims > 1 ? "Call panel here, \(sims) SIMs" : "Answer, decline and place calls here"
    }
}

// MARK: Phone

/// The paired phone: the Tether, its state, battery, network and sound, and the two actions
/// you reach for first.
private struct PhoneCard: View {
    @ObservedObject var model: AppModel
    @State private var showsDetails = false

    var body: some View {
        let status = DroidtoshStatus(statusText: model.connection.statusText)
        HStack(alignment: .center, spacing: 16) {
            TetherView(state: model.device.findCanStop ? .echo : status.tether, size: 42)
                .foregroundStyle(Brand.onInk)
                .frame(width: 60, height: 60)
                .background(RoundedRectangle(cornerRadius: 17, style: .continuous).fill(Brand.ink))
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Text(model.connection.connectedDevice ?? "Your phone")
                        .font(.title2.weight(.semibold))
                        .lineLimit(1)
                    StatusBadge(status: status, onHero: true)
                    Button {
                        showsDetails.toggle()
                    } label: {
                        Image(systemName: "info.circle")
                    }
                    .buttonStyle(.borderless)
                    .help("Phone details")
                    .accessibilityLabel("Phone details")
                    .popover(isPresented: $showsDetails, arrowEdge: .bottom) {
                        PhoneDetailsCard(device: model.device)
                            .frame(width: 320)
                            .padding(12)
                    }
                }
                Text(subtitle(status))
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if status != .connected && status != .notPaired {
                    if let detail = model.connection.disconnectCause?.detail {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    Button("Reconnect now") { model.connection.reconnectNow() }
                        .buttonStyle(OutlineButtonStyle(compact: true))
                        .help("Drop the connection to the relay and dial again at once")
                }
                if model.device.showsStatus {
                    PhoneStatusChips(device: model.device)
                        .padding(.top, 2)
                }
                if let text = FindPhoneText.status(model.device), model.device.showsFind {
                    Text(text)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .transition(.opacity)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 8) {
                Button {
                    model.device.toggleFind()
                } label: {
                    HStack(spacing: 7) {
                        if model.device.findCanStop {
                            TetherView(state: .echo, size: 16)
                        } else {
                            Image(systemName: "speaker.wave.2")
                        }
                        Text(model.device.findButtonTitle)
                    }
                    .frame(minWidth: 128)
                }
                .buttonStyle(InkButtonStyle())
                .disabled(!model.device.showsFind)
                .help(model.device.showsFind ? "Rings your phone so you can find it nearby" : "Available when your phone's Droidtosh app supports it")
                Button {
                    model.files.pickAndSend()
                } label: {
                    Label("Send File…", systemImage: "square.and.arrow.up")
                        .frame(minWidth: 128)
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(!model.files.phoneSupportsFiles)
                .help(model.files.phoneSupportsFiles ? "Choose files or folders to send" : "Connect your phone to send files")
            }
        }
        
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Brand.hero))
        .animation(.easeOut(duration: 0.2), value: model.device.findCanStop)
        .accessibilityElement(children: .contain)
    }

    private func subtitle(_ status: DroidtoshStatus) -> String {
        guard status == .connected else { return model.connection.statusHeadline }
        if model.connection.sessionPath == .local { return "Over the local network" }
        let host = model.connection.relayHost
        switch model.connection.connectionMode {
        case .both: return "Through \(host), local network as fallback"
        case .relay: return "Through \(host)"
        case .local: return "Over the local network"
        }
    }
}

/// Before pairing: what's left, and the button into the guided setup window.
private struct SetupCard: View {
    @ObservedObject var model: AppModel
    @Environment(\.showPairing) private var showPairing

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            TetherView(state: .seeking, size: 42)
                .foregroundStyle(Brand.onInk)
                .frame(width: 60, height: 60)
                .background(RoundedRectangle(cornerRadius: 17, style: .continuous).fill(Brand.ink))
            VStack(alignment: .leading, spacing: 8) {
                Text("Set up Droidtosh")
                    .font(.title2.weight(.semibold))
                Text("Pair your Android phone to get its notifications, calls, clipboard and files on this Mac.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 14) {
                    step("Connection", done: model.connectionConfigured)
                    step("Pair your phone", done: model.pairing.isPaired)
                    step("Notifications", done: model.notifications.notificationsAuthorized == true)
                }
                .padding(.top, 2)
            }
            Spacer(minLength: 8)
            Button("Set Up…") { showPairing() }
                .buttonStyle(InkButtonStyle())
        }
        
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Brand.hero))
    }

    private func step(_ title: String, done: Bool) -> some View {
        HStack(spacing: 5) {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? .primary : .tertiary)
            Text(title)
                .foregroundStyle(done ? .primary : .secondary)
        }
        .font(.caption.weight(.medium))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title)\(done ? ", done" : "")")
    }
}

// MARK: Sync cards

private struct SyncCard: View {
    enum Icon {
        case symbol(String)
        case tether(TetherState)
    }

    let title: String
    let detail: String
    let icon: Icon
    let isOn: Binding<Bool>?
    let open: (() -> Void)?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let open {
                Button(action: open) { cardContent }
                    .buttonStyle(.plain)
            } else {
                cardContent
            }
            if let isOn {
                Toggle(title, isOn: isOn.animation(.spring(response: 0.3, dampingFraction: 0.7)))
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .controlSize(.small)
                    .frame(height: 30)
                    .padding(12)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(CardBackground(cornerRadius: 12))
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .hoverLift()
    }

    private var cardContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                iconView
                    .frame(width: 30, height: 30)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Brand.tile))
                Spacer()
                if open != nil && isOn == nil {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.callout.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder
    private var iconView: some View {
        switch icon {
        case .symbol(let name):
            Image(systemName: name)
                .font(.system(size: 14, weight: .semibold))
        case .tether(let state):
            TetherView(state: state, size: 22)
        }
    }
}

// MARK: Recent activity

private struct RecentCallsCard: View {
    @ObservedObject var model: AppModel
    @Environment(\.selectSection) private var selectSection

    var body: some View {
        Card {
            HStack {
                Text("Recent calls").font(.headline)
                Spacer()
                Button("See All") { selectSection(.calls) }
                    .buttonStyle(.inkLink)
                    .font(.callout)
            }
            let entries = Array(model.callLog.entries.prefix(5))
            if entries.isEmpty {
                Text("Your phone's recent calls appear here once it connects.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        if index > 0 { Divider() }
                        row(entry)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func row(_ entry: DSCallLogEntry) -> some View {
        let missed = entry.type == .missed || entry.type == .rejected
        return HStack(spacing: 10) {
            ContactAvatar(name: entry.contactName, number: entry.number, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(CallText.title(entry))
                    .fontWeight(missed ? .semibold : .regular)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Text(CallText.typeLabel(entry.type))
                    if let date = CallText.date(entry) {
                        Text(date, format: .dateTime.hour().minute())
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 6)
            if missed {
                Button("Call Back") { model.calls.redial(entry) }
                    .buttonStyle(OutlineButtonStyle(compact: true))
            }
        }
        
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}

private struct RecentTransfersCard: View {
    @ObservedObject var model: AppModel
    @Environment(\.selectSection) private var selectSection
    @State private var dropTargeted = false

    private var recent: [FileTransferSnapshot] {
        let active = model.files.activeTransfers
        let finished = model.files.transfers.filter { $0.phase.isFinished }
        return Array((active + finished).prefix(5))
    }

    var body: some View {
        Card {
            HStack {
                Text("Transfers").font(.headline)
                Spacer()
                Button("See All") { selectSection(.files) }
                    .buttonStyle(.inkLink)
                    .font(.callout)
            }
            if recent.isEmpty {
                Text(model.files.phoneSupportsFiles ? "No transfers yet. Drop files here to send them to your phone." : "No transfers yet.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
            } else {
                VStack(spacing: 10) {
                    ForEach(recent) { transfer in
                        FileTransferRow(transfer: transfer, files: model.files, compact: true)
                    }
                }
            }
        }
        .overlay {
            if dropTargeted {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Brand.ink, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
            }
        }
        .sendFilesOnDrop(files: model.files, isTargeted: $dropTargeted)
        .frame(maxWidth: .infinity)
    }
}
