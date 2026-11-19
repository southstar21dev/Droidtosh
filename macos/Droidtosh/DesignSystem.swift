import AppKit
import SwiftUI
import DroidsyncKit

// The shared look of Droidtosh's windows, popover and panels: black, white and greys, cards,
// the attention line, motion helpers and the Liquid Glass helpers (with material fallbacks
// before macOS 26).

enum Brand {
    /// The Droid Mac logo from the app icon, as a template image.
    static let glyph = Image("BrandGlyph")

    // Black and white only: ink on paper in light mode, and the reverse in dark mode. State is
    // carried by words, weight and the Tether, never by colour.

    /// The strongest foreground: black on light, white on dark.
    static let ink = Color(light: 0x000000, dark: 0xFFFFFF)
    /// Text and symbols on `ink`.
    static let onInk = Color(light: 0xFFFFFF, dark: 0x000000)
    /// The window background behind the content.
    static let surface = Color(light: 0xFFFFFF, dark: 0x161616)
    /// Cards and grouped content on `surface`.
    static let card = Color(light: 0xF7F7F7, dark: 0x202020)
    /// The phone card on Home: a quiet grey panel.
    static let hero = Color(light: 0xF0F0F0, dark: 0x262626)
    /// Text and symbols on `hero`.
    static let onHero = Color(light: 0x000000, dark: 0xFFFFFF)
    /// The fill of icon tiles and other tonal containers.
    static let tile = Color(light: 0xE8E8E8, dark: 0x333333)
    /// Symbols on `tile`.
    static let onTile = Color(light: 0x000000, dark: 0xFFFFFF)
    /// Greys for per-app monograms; white text reads on each.
    static let monograms: [Color] = [0x1A1A1A, 0x333333, 0x4D4D4D, 0x5C5C5C, 0x262626, 0x404040, 0x555555, 0x0D0D0D].map { Color(hex: $0) }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    /// A colour that follows the view's appearance.
    init(light: UInt32, dark: UInt32) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat(hex >> 16 & 0xFF) / 255, green: CGFloat(hex >> 8 & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

// MARK: Glass and materials

extension View {
    /// A floating surface: Liquid Glass on macOS 26, a translucent material before.
    @ViewBuilder
    func droidtoshGlass(cornerRadius: CGFloat = 16, interactive: Bool = false) -> some View {
        if #available(macOS 26.0, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            self
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(.separator, lineWidth: 0.5)
                )
        }
    }

    /// Secondary actions: glass buttons on macOS 26, bordered before.
    @ViewBuilder
    func droidtoshButton() -> some View {
        if #available(macOS 26.0, *) { self.buttonStyle(.glass) } else { self.buttonStyle(.bordered) }
    }

    /// The primary action of a group: an ink button.
    func droidtoshProminentButton() -> some View {
        buttonStyle(InkButtonStyle())
    }
}

/// The primary button: ink fill with paper text, a slight press.
struct InkButtonStyle: ButtonStyle {
    var compact = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(compact ? .callout.weight(.semibold) : .body.weight(.semibold))
            .foregroundStyle(Brand.onInk)
            .padding(.horizontal, compact ? 12 : 16)
            .padding(.vertical, compact ? 5 : 8)
            .background(RoundedRectangle(cornerRadius: compact ? 8 : 10, style: .continuous).fill(Brand.ink))
            .opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.35)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A secondary button: outlined, same shape as `InkButtonStyle`.
struct OutlineButtonStyle: ButtonStyle {
    var compact = false
    @Environment(\.isEnabled) private var isEnabled
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(compact ? .callout.weight(.medium) : .body.weight(.medium))
            .foregroundStyle(.primary)
            .padding(.horizontal, compact ? 12 : 16)
            .padding(.vertical, compact ? 5 : 8)
            .background(
                RoundedRectangle(cornerRadius: compact ? 8 : 10, style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.1 : hovering ? 0.06 : 0.0))
            )
            .overlay(
                RoundedRectangle(cornerRadius: compact ? 8 : 10, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.22), lineWidth: 1)
            )
            .opacity(isEnabled ? 1 : 0.35)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
            .onHover { hovering = $0 }
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A text link in ink, underlined on hover, instead of the blue system link.
struct InkLinkButtonStyle: ButtonStyle {
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .fontWeight(.medium)
            .foregroundStyle(.primary)
            .underline(hovering)
            .opacity(configuration.isPressed ? 0.6 : 1)
            .onHover { hovering = $0 }
            .contentShape(Rectangle())
    }
}

extension ButtonStyle where Self == InkLinkButtonStyle {
    static var inkLink: InkLinkButtonStyle { InkLinkButtonStyle() }
}

// MARK: Motion

private struct EntranceModifier: ViewModifier {
    let delay: Double
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(shown || reduceMotion ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 10)
            .onAppear {
                guard !shown else { return }
                withAnimation(.spring(response: 0.5, dampingFraction: 0.85).delay(delay)) { shown = true }
            }
    }
}

private struct HoverLiftModifier: ViewModifier {
    let cornerRadius: CGFloat
    @State private var hovering = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .shadow(color: .black.opacity(hovering ? 0.10 : 0), radius: hovering ? 10 : 0, y: hovering ? 3 : 0)
            .offset(y: hovering && !reduceMotion ? -1 : 0)
            .animation(.easeOut(duration: 0.18), value: hovering)
            .onHover { hovering = $0 }
    }
}

extension View {
    /// Rises into place when it first appears; staggered by `delay`.
    func entrance(_ delay: Double = 0) -> some View {
        modifier(EntranceModifier(delay: delay))
    }

    /// A soft shadow and a one-point lift on hover.
    func hoverLift(cornerRadius: CGFloat = 12) -> some View {
        modifier(HoverLiftModifier(cornerRadius: cornerRadius))
    }
}

// MARK: Cards

/// A content card in a window: a quiet grey fill like a grouped form section, so content stays
/// in the content layer and glass is kept for controls.
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(CardBackground(cornerRadius: 12))
    }
}

struct CardBackground: View {
    var cornerRadius: CGFloat = 12
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Brand.card)
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(contrast == .increased ? AnyShapeStyle(.secondary) : AnyShapeStyle(.separator), lineWidth: contrast == .increased ? 1 : 0.5)
            )
    }
}

/// A card title: an SF Symbol and a headline, with an optional trailing accessory.
struct CardHeader<Accessory: View>: View {
    let title: String
    let symbol: String
    @ViewBuilder var accessory: Accessory

    init(_ title: String, symbol: String, @ViewBuilder accessory: () -> Accessory) {
        self.title = title
        self.symbol = symbol
        self.accessory = accessory()
    }

    var body: some View {
        HStack(spacing: 8) {
            Label(title, systemImage: symbol)
                .font(.headline)
                .labelStyle(TintedIconLabelStyle())
            Spacer(minLength: 8)
            accessory
        }
    }
}

extension CardHeader where Accessory == EmptyView {
    init(_ title: String, symbol: String) {
        self.init(title, symbol: symbol) { EmptyView() }
    }
}

/// A label whose icon is quieter than its title, as in System Settings section titles.
struct TintedIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon.foregroundStyle(.secondary)
            configuration.title
        }
    }
}

/// An SF Symbol on a rounded grey square, or on ink when `emphasized`.
struct IconTile: View {
    let symbol: String
    var emphasized = false
    var size: CGFloat = 26

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(emphasized ? Brand.onInk : Brand.onTile)
            .frame(width: size, height: size)
            .background(
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                    .fill(emphasized ? Brand.ink : Brand.tile)
            )
            .accessibilityHidden(true)
    }
}

/// The logo on an ink tile: the app's "avatar" in Settings.
struct BrandBadge: View {
    var size: CGFloat = 32

    var body: some View {
        Brand.glyph
            .resizable()
            .renderingMode(.template)
            .scaledToFit()
            .foregroundStyle(Brand.onInk)
            .padding(size * 0.14)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous).fill(Brand.ink))
            .accessibilityHidden(true)
    }
}

// MARK: Banners

/// An inline problem or hint with an optional action.
struct Banner<Actions: View>: View {
    enum Style {
        case warning
        case info
        case error
    }

    let style: Style
    let symbol: String
    let title: String
    var message: String?
    @ViewBuilder var actions: Actions

    init(_ style: Style, symbol: String, title: String, message: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.style = style
        self.symbol = symbol
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(width: 20)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.callout.weight(.semibold))
                if let message {
                    Text(message)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 8) { actions }
                    .controlSize(.small)
                    .padding(.top, 3)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.primary.opacity(style == .info ? 0.04 : 0.07))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.primary.opacity(style == .info ? 0.12 : 0.3), lineWidth: style == .info ? 0.5 : 1)
        )
        .accessibilityElement(children: .combine)
    }
}

extension Banner where Actions == EmptyView {
    init(_ style: Style, symbol: String, title: String, message: String? = nil) {
        self.init(style, symbol: symbol, title: title, message: message) { EmptyView() }
    }
}

// MARK: Status

/// The Tether and a label for the connection state.
struct StatusBadge: View {
    let status: DroidtoshStatus
    var onHero = false

    var body: some View {
        HStack(spacing: 5) {
            TetherView(state: status.tether, size: 14)
            Text(status.title)
                .font(.caption.weight(.semibold))
        }
        .padding(.leading, 5)
        .padding(.trailing, 8)
        .padding(.vertical, 3)
        .foregroundStyle(.primary)
        .background(Capsule().fill(onHero ? Brand.surface : Color.primary.opacity(0.07)))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Status: \(status.title)")
    }
}

// MARK: Attention line

/// The one thing that needs the user right now, shown as a single line instead of a stack of
/// banners: in the main window's toolbar and at the bottom of the popover.
struct AttentionItem: Identifiable {
    enum Action {
        case setup
        case installUpdate
        case openNotificationSettings
        case none
    }

    let id: String
    let symbol: String
    let title: String
    let detail: String?
    let actionTitle: String?
    let action: Action

    /// Most important first; the attention line shows the first.
    @MainActor
    static func items(for model: AppModel) -> [AttentionItem] {
        var items: [AttentionItem] = []
        if let failure = model.pairing.saveFailure {
            items.append(AttentionItem(id: "save-failed", symbol: "key.slash", title: "Couldn't save the pairing key", detail: "\(failure) The phone must pair again after a relaunch.", actionTitle: nil, action: .none))
        }
        if !model.connectionConfigured {
            items.append(AttentionItem(id: "connection", symbol: "network.slash", title: "Choose how your phone connects", detail: "Set a relay address, or choose Local network, to pair.", actionTitle: "Set Up", action: .setup))
        } else if !model.pairing.isPaired && !model.connection.phoneConnected {
            items.append(AttentionItem(id: "pair", symbol: "qrcode", title: "Pair your phone", detail: "Scan the pairing code with Droidtosh on Android.", actionTitle: "Show Code", action: .setup))
        }
        if case .available(let offer) = model.updates.state {
            items.append(AttentionItem(id: "update", symbol: "arrow.down.circle", title: "Droidtosh \(offer.version) is ready", detail: "Install it and relaunch.", actionTitle: "Install", action: .installUpdate))
        }
        if model.device.phoneBackgroundRestricted {
            items.append(AttentionItem(id: "background", symbol: "battery.50percent", title: PhoneStatusDisplay.backgroundTitle, detail: PhoneStatusDisplay.backgroundDetail, actionTitle: nil, action: .none))
        }
        if model.notifications.notificationsAuthorized == false {
            items.append(AttentionItem(id: "blocked", symbol: "bell.slash", title: "Notifications are blocked", detail: "Allow them for Droidtosh in System Settings.", actionTitle: "Open Settings", action: .openNotificationSettings))
        }
        if !model.notifications.notificationsRelayEnabled {
            items.append(AttentionItem(id: "relay-off", symbol: "exclamationmark.triangle", title: "Notification relay is off", detail: "Turn it on in the Droidtosh app on your phone.", actionTitle: nil, action: .none))
        }
        return items
    }

    @MainActor
    func perform(model: AppModel, setup: () -> Void) {
        switch action {
        case .setup: setup()
        case .installUpdate: model.updates.install()
        case .openNotificationSettings: SystemSettings.openNotifications()
        case .none: break
        }
    }
}

/// A capsule with the most important attention item; more items show as a count with a menu.
struct AttentionLine: View {
    let items: [AttentionItem]
    let perform: (AttentionItem) -> Void

    var body: some View {
        if let first = items.first {
            HStack(spacing: 6) {
                Button {
                    perform(first)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: first.symbol)
                            .font(.caption.weight(.semibold))
                        Text(first.title)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                        if let actionTitle = first.actionTitle {
                            Text(actionTitle)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Brand.onInk)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(Brand.ink))
                        }
                    }
                    .padding(.leading, 10)
                    .padding(.trailing, first.actionTitle == nil ? 10 : 4)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(Color.primary.opacity(0.06)))
                    .overlay(Capsule().strokeBorder(Color.primary.opacity(0.18), lineWidth: 0.5))
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .help(first.detail ?? first.title)
                if items.count > 1 {
                    Menu {
                        ForEach(items.dropFirst()) { item in
                            Button(item.title) { perform(item) }
                        }
                    } label: {
                        Text("+\(items.count - 1)")
                            .font(.caption.weight(.semibold))
                            .monospacedDigit()
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("More things that need attention")
                }
            }
            .transition(.opacity.combined(with: .scale(scale: 0.95)))
            .accessibilityElement(children: .contain)
        }
    }
}

// MARK: Shared actions

enum SystemSettings {
    static func openNotifications() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
            NSWorkspace.shared.open(url)
        }
    }
}

/// Opens one of the app's windows from anywhere (popover, panels), bringing the app forward.
@MainActor
struct WindowOpener {
    let openWindow: OpenWindowAction
    let openSettings: OpenSettingsAction

    func main() {
        openWindow(id: "main")
        bringForward()
    }

    /// The guided setup window, which also shows the pairing code.
    func pairing() {
        openWindow(id: "pairing")
        bringForward()
    }

    func settings() {
        WindowActivation.present()
        openSettings()
        bringForward()
    }

    private func bringForward() {
        WindowActivation.present()
        DispatchQueue.main.async { WindowActivation.present() }
    }
}

extension EnvironmentValues {
    /// The main window's sidebar selection, so cards can jump to a section.
    @Entry var selectSection: (MainSection) -> Void = { _ in }
}
