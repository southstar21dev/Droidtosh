import AppKit
import SwiftUI

// The shared look of Droidtosh's windows, popover and panels: brand colours, cards, banners
// and the Liquid Glass helpers (with material fallbacks before macOS 26).

enum Brand {
    /// Lagoon #0E9F8E, the brand tint (also the app's accent colour asset).
    static let lagoon = Color("Lagoon")
    /// Indigo #4B5BD7, the secondary brand colour.
    static let indigo = Color("Indigo")
    /// Lagoon → Indigo at 135°. Only for the app icon and hero or illustration surfaces.
    /// Fixed sRGB values (not the appearance-adaptive assets), so it reads the same in dark mode.
    static let gradient = LinearGradient(
        colors: [Color(red: 0x0E / 255, green: 0x9F / 255, blue: 0x8E / 255), Color(red: 0x4B / 255, green: 0x5B / 255, blue: 0xD7 / 255)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
    /// Cream #F5ECD7, the app-icon tile behind the logo.
    static let cream = Color(red: 0xF5 / 255, green: 0xEC / 255, blue: 0xD7 / 255)
    /// Dark roast #1F1A17, the logo itself.
    static let darkRoast = Color(red: 0x1F / 255, green: 0x1A / 255, blue: 0x17 / 255)
    /// The Droid Mac logo from the app icon, as a template image.
    static let glyph = Image("BrandGlyph")
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

/// Groups glass shapes so neighbouring ones blend and morph together (macOS 26).
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder var content: Content

    var body: some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

// MARK: Cards

/// A content card in a window: a quiet fill like a grouped form section, so content stays in
/// the content layer and glass is kept for controls.
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
            .fill(Color(nsColor: .quaternarySystemFill))
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

/// A white SF Symbol on a coloured rounded square, as in System Settings.
struct IconTile: View {
    let symbol: String
    var color: Color = .accentColor
    var size: CGFloat = 26

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                    .fill(color.gradient)
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
    var onGradient = false

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(onGradient ? Color.white : status.color)
                .frame(width: 7, height: 7)
                .overlay {
                    if status == .connected && !onGradient {
                        Circle().stroke(status.color.opacity(0.35), lineWidth: 3)
                    }
                }
            Text(status.title)
                .font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .foregroundStyle(onGradient ? AnyShapeStyle(.white) : AnyShapeStyle(status.color))
        .background(Capsule().fill(onGradient ? Color.white.opacity(0.2) : status.color.opacity(0.14)))
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
