import SwiftUI
import DroidsyncKit

/// The main window's sections. Each page carries its own options; the Settings window keeps
/// only General and Updates.
enum MainSection: String, CaseIterable, Identifiable {
    case home
    case notifications
    case calls
    case files
    case connection

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .notifications: return "Notifications"
        case .calls: return "Calls"
        case .files: return "Files"
        case .connection: return "Connection"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .notifications: return "bell"
        case .calls: return "phone"
        case .files: return "folder"
        case .connection: return "arrow.left.arrow.right"
        }
    }
}

struct MainView: View {
    @ObservedObject var model: AppModel
    @State private var selection: MainSection?
    @State private var showsPairing = false
    @Environment(\.openSettings) private var openSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(model: AppModel, initialSection: MainSection = .home) {
        self.model = model
        _selection = State(initialValue: initialSection)
    }

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    SidebarPhoneCard(model: model)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 10, trailing: 0))
                        .selectionDisabled()
                    sidebarRow(.home)
                    sidebarRow(.notifications)
                    sidebarRow(.calls)
                    sidebarRow(.files)
                }
                Section("This Mac") {
                    sidebarRow(.connection)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 200, ideal: 228, max: 280)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button {
                    WindowActivation.present()
                    openSettings()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "gearshape")
                            .frame(width: 18)
                        Text("Settings")
                        Spacer()
                        Text("\u{2318},")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .contentShape(Rectangle())
                }
                .buttonStyle(SidebarFooterButtonStyle())
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
            }
        } detail: {
            // Fill the column first: empty states size to their content, and the surface would
            // otherwise paint only behind them.
            detail
                .scrollContentBackground(.hidden)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Brand.surface)
                .frame(minWidth: 620, minHeight: 520)
                .id(selection)
                .transition(.opacity)
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: selection)
        .environment(\.selectSection, { selection = $0 })
        .environment(\.showPairing, { showsPairing = true })
        .sheet(isPresented: $showsPairing) {
            SetupView(model: model, onDone: { showsPairing = false })
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
        switch selection ?? .home {
        case .home:
            HomeView(model: model)
        case .notifications:
            NotificationsView(model: model)
        case .calls:
            CallsView(model: model)
        case .files:
            FilesView(model: model)
        case .connection:
            ConnectionView(model: model)
        }
    }
}

private struct SidebarFooterButtonStyle: ButtonStyle {
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.1 : hovering ? 0.06 : 0))
            )
            .onHover { hovering = $0 }
            .animation(.easeOut(duration: 0.15), value: hovering)
    }
}

/// The paired phone at the top of the sidebar: the Tether, its name and its state.
private struct SidebarPhoneCard: View {
    @ObservedObject var model: AppModel
    @Environment(\.selectSection) private var selectSection

    var body: some View {
        let status = DroidtoshStatus(statusText: model.connection.statusText)
        Button {
            selectSection(.home)
        } label: {
            HStack(spacing: 10) {
                TetherView(state: status.tether, size: 24)
                    .foregroundStyle(Brand.onInk)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Brand.ink))
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.connection.connectedDevice ?? (model.pairing.isPaired ? "Your phone" : "No phone"))
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    Text(detail(status))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(9)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Brand.surface)
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Phone: \(model.connection.connectedDevice ?? "none"), \(status.title)")
    }

    private func detail(_ status: DroidtoshStatus) -> String {
        if let device = model.device.status, model.device.phoneSupportsStatus || model.device.isStale {
            return "\(status.title), \(PhoneStatusDisplay.batteryText(device))"
        }
        return status.title
    }
}

extension EnvironmentValues {
    /// Shows the setup and pairing sheet in the main window.
    @Entry var showPairing: () -> Void = {}
}
