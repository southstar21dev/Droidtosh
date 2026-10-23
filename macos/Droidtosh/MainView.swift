import SwiftUI
import DroidsyncKit

/// The main window's sections. Settings live in the app's Settings window.
enum MainSection: String, CaseIterable, Identifiable {
    case overview
    case notifications
    case calls
    case files
    case diagnostics

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: return "Overview"
        case .notifications: return "Notifications"
        case .calls: return "Calls"
        case .files: return "Files"
        case .diagnostics: return "Diagnostics"
        }
    }

    var symbol: String {
        switch self {
        case .overview: return "iphone.gen3"
        case .notifications: return "bell.badge"
        case .calls: return "phone"
        case .files: return "folder"
        case .diagnostics: return "waveform.path.ecg"
        }
    }
}

struct MainView: View {
    @ObservedObject var model: AppModel
    @State private var selection: MainSection?
    @State private var showsPairing = false

    init(model: AppModel, initialSection: MainSection = .overview) {
        self.model = model
        _selection = State(initialValue: initialSection)
    }

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    sidebarRow(.overview)
                }
                Section("Phone") {
                    sidebarRow(.notifications)
                    sidebarRow(.calls)
                    sidebarRow(.files)
                }
                Section("Support") {
                    sidebarRow(.diagnostics)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 280)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                SidebarDeviceFooter(model: model)
            }
        } detail: {
            detail
                .scrollContentBackground(.hidden)
                .background(Brand.surface)
                .frame(minWidth: 560, minHeight: 480)
        }
        .environment(\.selectSection, { selection = $0 })
        .environment(\.showPairing, { showsPairing = true })
        .sheet(isPresented: $showsPairing) {
            PairingView(model: model, onDone: { showsPairing = false })
        }
    }

    private func sidebarRow(_ section: MainSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .badge(badge(for: section))
            .tag(section)
    }

    private func badge(for section: MainSection) -> Int {
        switch section {
        case .files: return model.files.activeTransfers.count
        default: return 0
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch selection ?? .overview {
        case .overview:
            OverviewView(model: model)
        case .notifications:
            NotificationsView(model: model)
        case .calls:
            CallsView(model: model)
        case .files:
            FilesView(model: model)
        case .diagnostics:
            DiagnosticsView(diagnostics: model.diagnostics)
        }
    }
}

/// The paired phone at the bottom of the sidebar: name, connection state and battery.
private struct SidebarDeviceFooter: View {
    @ObservedObject var model: AppModel

    var body: some View {
        let status = DroidtoshStatus(statusText: model.connection.statusText)
        HStack(spacing: 10) {
            Image(systemName: "iphone.gen3")
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(model.connection.connectedDevice ?? "No phone")
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Circle().fill(status.color).frame(width: 6, height: 6)
                    Text(status.title)
                    if let status = model.device.status, model.device.phoneSupportsStatus || model.device.isStale {
                        Text("·")
                        Text(PhoneStatusDisplay.batteryText(status)).monospacedDigit()
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Phone: \(model.connection.connectedDevice ?? "none"), \(status.title)")
    }
}

extension EnvironmentValues {
    /// Shows the pairing sheet in the main window.
    @Entry var showPairing: () -> Void = {}
}

