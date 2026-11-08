import AppKit
import SwiftUI

// The shared look of Droidtosh's windows, popover and panels: the Roast & Cream brand colours, cards, banners
// and the Liquid Glass helpers (with material fallbacks before macOS 26).

enum Brand {
    /// Cream #F5ECD7, the app-icon tile behind the logo.
    static let cream = Color(hex: 0xF5ECD7)
    /// Dark roast #1F1A17, the logo itself.
    static let darkRoast = Color(hex: 0x1F1A17)
    /// Ring cream #E6D8B8, a deeper cream for containers on light.
    static let ringCream = Color(hex: 0xE6D8B8)
    /// Tan #A8946A, the quiet accent of the southstar21 family.
    static let tan = Color(hex: 0xA8946A)
    /// The Droid Mac logo from the app icon, as a template image.
    static let glyph = Image("BrandGlyph")

    // Roast & Cream, as on Android: the hero is the inverse surface (dark roast on light, cream
    // on dark), and icon tiles are tonal cream–roast containers. Flat colour only, no gradients.

    /// The window background behind the content, as Android's surface.
    static let surface = Color(light: 0xFBF7EE, dark: 0x1F1A17)
    /// Cards and grouped content on `surface`, as Android's surface container.
    static let card = Color(light: 0xF5ECD7, dark: 0x2A2320)
    /// The hero surface: dark roast in light mode, cream in dark mode.
    static let hero = Color(light: 0x1F1A17, dark: 0xF5ECD7)
    /// Text and symbols on `hero`.
    static let onHero = Color(light: 0xF5ECD7, dark: 0x1F1A17)
    /// The fill of icon tiles and other tonal containers.
    static let tile = Color(light: 0xE6D8B8, dark: 0x4A3F33)
    /// Symbols on `tile`.
    static let onTile = Color(light: 0x1F1A17, dark: 0xF5ECD7)
    /// Warm tones for per-app monograms, from the cream–roast ramp; white text reads on each.
    static let monograms: [Color] = [0x6E5E44, 0x8A5300, 0x4A3F33, 0x7A4E2D, 0x5E6B4A, 0x8C6A4F, 0x3A3129, 0x96743A].map { Color(hex: $0) }
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

    /// The primary action of a group: tinted glass on macOS 26, bordered prominent before.
    @ViewBuilder
    func droidtoshProminentButton() -> some View {
        if #available(macOS 26.0, *) { self.buttonStyle(.glassProminent) } else { self.buttonStyle(.borderedProminent) }
    }
}

// MARK: Cards

/// A content card in a window: a quiet cream (or roast) fill like a grouped form section, so
/// content stays in the content layer and glass is kept for controls.
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

/// A label whose icon takes the accent colour, as in System Settings section titles.
struct TintedIconLabelStyle: LabelStyle {
    var tint: Color = .accentColor

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon.foregroundStyle(tint)
            configuration.title
        }
    }
}

/// An SF Symbol on a rounded square: roast on a cream tile by default, or white on a status
/// colour (green, orange, red) when the tile reports a state.
struct IconTile: View {
    let symbol: String
    var color: Color? = nil
    var size: CGFloat = 26

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(color == nil ? Brand.onTile : .white)
            .frame(width: size, height: size)
            .background(
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                    .fill(color ?? Brand.tile)
            )
            .accessibilityHidden(true)
    }
}

/// The logo as on the app icon (dark roast Droid Mac on cream): the app's "avatar" in headers and onboarding.
struct BrandBadge: View {
    var size: CGFloat = 32

    var body: some View {
        Brand.glyph
            .resizable()
            .renderingMode(.template)
            .scaledToFit()
            .foregroundStyle(Brand.darkRoast)
            .padding(size * 0.1)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: size * 0.27, style: .continuous).fill(Brand.cream))
            .shadow(color: .black.opacity(0.18), radius: size * 0.08, y: size * 0.04)
            .accessibilityHidden(true)
    }
}

// MARK: Banners

/// An inline problem or hint with an optional action, used in the popover and the overview.
struct Banner<Actions: View>: View {
    enum Style {
        case warning
        case info
        case error

        var color: Color {
            switch self {
            case .warning: return .orange
            case .info: return .accentColor
            case .error: return .red
            }
        }
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
                .foregroundStyle(style.color)
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
                .fill(style.color.opacity(0.1))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(style.color.opacity(0.28), lineWidth: 0.5)
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

/// Coloured dot and label for the connection state.
struct StatusBadge: View {
    let status: DroidtoshStatus
    var onHero = false

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(onHero ? Brand.onHero : status.color)
                .frame(width: 7, height: 7)
                .overlay {
                    if status == .connected && !onHero {
                        Circle().stroke(status.color.opacity(0.35), lineWidth: 3)
                    }
                }
            Text(status.title)
                .font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .foregroundStyle(onHero ? AnyShapeStyle(Brand.onHero) : AnyShapeStyle(status.color))
        .background(Capsule().fill(onHero ? Brand.onHero.opacity(0.16) : status.color.opacity(0.14)))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Status: \(status.title)")
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
