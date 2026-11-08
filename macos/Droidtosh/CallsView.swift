import SwiftUI
import DroidsyncKit

/// Place calls through the phone and browse its call log.
struct CallsView: View {
    @ObservedObject var model: AppModel

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All"
        case incoming = "Incoming"
        case outgoing = "Outgoing"
        case missed = "Missed"

        var id: String { rawValue }
    }

    @State private var number = ""
    @State private var selectedAccount = ""
    @State private var filter: Filter = .all
    @State private var search = ""

    private var effectiveAccount: String {
        selectedAccount.isEmpty ? (model.calls.phoneAccounts.first?.id ?? "") : selectedAccount
    }

    private var accountBinding: Binding<String> {
        Binding(get: { effectiveAccount }, set: { selectedAccount = $0 })
    }

    private var entries: [DSCallLogEntry] {
        model.callLog.entries.filter { matchesFilter($0) && matchesSearch($0) }
    }

    /// Entries grouped by day, newest first (the phone pages them newest first).
    private var days: [(day: Date, entries: [DSCallLogEntry])] {
        var groups: [(day: Date, entries: [DSCallLogEntry])] = []
        let calendar = Calendar.current
        for entry in entries {
            let day = calendar.startOfDay(for: CallText.date(entry) ?? .distantPast)
            if let last = groups.indices.last, groups[last].day == day {
                groups[last].entries.append(entry)
            } else {
                groups.append((day, [entry]))
            }
        }
        return groups
    }

    var body: some View {
        VStack(spacing: 0) {
            dialer
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
            Divider()
            callLog
        }
        .navigationTitle("Calls")
        .searchable(text: $search, placement: .toolbar, prompt: "Search calls")
        .onAppear {
            #if DEBUG
            if SnapshotRenderer.isActive { return }
            #endif
            model.callLog.request(reset: true)
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Show", selection: $filter) {
                    ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.callLog.request(reset: true)
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .help("Reload the call log from the phone")
            }
        }
    }

    // MARK: Dialer

    private var trimmedNumber: String { number.trimmingCharacters(in: .whitespaces) }

    private func placeCall() {
        guard !trimmedNumber.isEmpty else { return }
        model.calls.dial(number: number, phoneAccountId: effectiveAccount.isEmpty ? nil : effectiveAccount)
    }

    private var dialer: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "circle.grid.3x3.fill")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                TextField("Enter a phone number", text: $number)
                    .textFieldStyle(.plain)
                    .font(.title3)
                    .onSubmit(placeCall)
                    .accessibilityLabel("Phone number")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(CardBackground(cornerRadius: 10))
            .frame(maxWidth: 340)
            if model.calls.phoneAccounts.count > 1 {
                Picker("SIM", selection: accountBinding) {
                    ForEach(model.calls.phoneAccounts, id: \.id) { account in
                        Text(account.label.isEmpty ? account.id : account.label).tag(account.id)
                    }
                }
                .labelsHidden()
                .fixedSize()
                .help("Which SIM to call from")
            }
            Button(action: placeCall) {
                Label("Call", systemImage: "phone.fill")
                    .padding(.horizontal, 6)
            }
            .controlSize(.large)
            .droidtoshProminentButton()
            .tint(.green)
            .disabled(trimmedNumber.isEmpty)
            .keyboardShortcut(.defaultAction)
            if !model.notifications.voipApps.isEmpty {
                Menu {
                    ForEach(model.notifications.voipApps, id: \.packageName) { app in
                        Button(model.notifications.appName(for: app.packageName)) {
                            model.notifications.openApp(package: app.packageName)
                        }
                    }
                } label: {
                    Label("Call via App", systemImage: "video.fill")
                }
                .controlSize(.large)
                .fixedSize()
                .help("Open a calling app on your phone")
            }
            Spacer(minLength: 0)
        }
        .onChange(of: model.calls.phoneAccounts) { _, accounts in
            if !accounts.contains(where: { $0.id == selectedAccount }) {
                selectedAccount = accounts.first?.id ?? ""
            }
        }
        .onAppear {
            if selectedAccount.isEmpty {
                selectedAccount = model.calls.phoneAccounts.first?.id ?? ""
            }
        }
    }

    // MARK: Log

    private var callLog: some View {
        List {
            ForEach(Array(days.enumerated()), id: \.offset) { _, group in
                Section(CallText.dayTitle(group.day)) {
                    ForEach(group.entries, id: \.id) { entry in
                        CallLogRow(entry: entry) { model.calls.redial(entry) }
                    }
                }
            }
            if model.callLog.hasMore {
                HStack {
                    Spacer()
                    Button("Load More") { model.callLog.loadMore() }
                    Spacer()
                }
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.inset)
        .overlay {
            if entries.isEmpty {
                if model.callLog.entries.isEmpty {
                    ContentUnavailableView(
                        "No Calls Yet",
                        systemImage: "phone",
                        description: Text("Your phone's recent calls appear here once it connects.")
                    )
                } else {
                    ContentUnavailableView(
                        "No Matches",
                        systemImage: "magnifyingglass",
                        description: Text("Try a different search or filter.")
                    )
                }
            }
        }
    }

    private func matchesFilter(_ entry: DSCallLogEntry) -> Bool {
        switch filter {
        case .all: return true
        case .incoming: return entry.type == .incoming
        case .outgoing: return entry.type == .outgoing
        case .missed: return entry.type == .missed || entry.type == .rejected
        }
    }

    private func matchesSearch(_ entry: DSCallLogEntry) -> Bool {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return true }
        return entry.contactName.localizedCaseInsensitiveContains(query)
            || entry.number.localizedCaseInsensitiveContains(query)
    }
}

private struct CallLogRow: View {
    let entry: DSCallLogEntry
    let callBack: () -> Void
    @State private var hovering = false

    private var missed: Bool { entry.type == .missed || entry.type == .rejected }

    var body: some View {
        HStack(spacing: 12) {
            ContactAvatar(name: entry.contactName, number: entry.number, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(CallText.title(entry))
                    .foregroundStyle(missed ? .red : .primary)
                    .fontWeight(missed ? .medium : .regular)
                HStack(spacing: 4) {
                    Image(systemName: CallText.symbol(entry.type))
                        .imageScale(.small)
                    Text(CallText.detail(entry))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            if hovering {
                Button(action: callBack) {
                    Image(systemName: "phone.fill")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.green)
                .help("Call back")
                .accessibilityLabel("Call back")
            }
            if let date = CallText.date(entry) {
                Text(date, format: .dateTime.hour().minute())
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .contextMenu {
            Button("Call Back", action: callBack)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Call back", callBack)
    }
}

/// Initials (or a person symbol) in a tinted circle.
struct ContactAvatar: View {
    let name: String
    let number: String
    var size: CGFloat = 32
    var onHero = false

    var body: some View {
        Group {
            if let initials {
                Text(initials)
                    .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.45))
            }
        }
        .foregroundStyle(onHero ? Brand.onHero : Brand.onTile)
        .frame(width: size, height: size)
        .background(Circle().fill(onHero ? Brand.onHero.opacity(0.18) : Brand.tile))
        .accessibilityHidden(true)
    }

    private var initials: String? {
        let words = name.split(separator: " ").prefix(2)
        guard !words.isEmpty, words.allSatisfy({ $0.first?.isLetter == true }) else { return nil }
        return words.compactMap(\.first).map(String.init).joined().uppercased()
    }
}

enum CallText {
    static func date(_ entry: DSCallLogEntry) -> Date? {
        entry.startedAtMs > 0 ? Date(timeIntervalSince1970: Double(entry.startedAtMs) / 1000) : nil
    }

    static func title(_ entry: DSCallLogEntry) -> String {
        if !entry.contactName.isEmpty { return entry.contactName }
        if !entry.number.isEmpty { return entry.number }
        return "Unknown"
    }

    static func symbol(_ type: DSCallLogType) -> String {
        switch type {
        case .outgoing: return "phone.arrow.up.right"
        case .missed, .rejected: return "phone.down"
        default: return "phone.arrow.down.left"
        }
    }

    static func typeLabel(_ type: DSCallLogType) -> String {
        switch type {
        case .outgoing: return "Outgoing"
        case .missed: return "Missed"
        case .rejected: return "Declined"
        default: return "Incoming"
        }
    }

    static func detail(_ entry: DSCallLogEntry) -> String {
        var parts = [typeLabel(entry.type)]
        if !entry.contactName.isEmpty, !entry.number.isEmpty { parts.append(entry.number) }
        if entry.durationS > 0 { parts.append(duration(entry.durationS)) }
        if !entry.simLabel.isEmpty { parts.append(entry.simLabel) }
        return parts.joined(separator: " · ")
    }

    static func duration(_ seconds: UInt32) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
        formatter.unitsStyle = .abbreviated
        return formatter.string(from: TimeInterval(seconds)) ?? "\(seconds)s"
    }

    static func dayTitle(_ day: Date) -> String {
        if day == .distantPast { return "Earlier" }
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return "Today" }
        if calendar.isDateInYesterday(day) { return "Yesterday" }
        return day.formatted(.dateTime.weekday(.wide).month().day())
    }
}
