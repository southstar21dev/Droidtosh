import AppKit
import SwiftUI

/// What the Tether shows: the link between the phone and this Mac, as two points and what
/// travels between them.
enum TetherState: Equatable {
    /// Connected. A pulse circles between the two sides and each beats as it lands.
    case linked
    /// Waiting for the phone: this Mac calls out while the phone circles in.
    case seeking
    /// Pairing or reconnecting: the two sides spin in, meet and lock.
    case handshake
    /// A file is moving, toward the phone or toward this Mac.
    case streaming(toPhone: Bool)
    /// A notification or a copy crosses over and fills the other side.
    case mirroring
    /// A call is coming in: the phone shakes inside its own waves.
    case ringing
    /// Find My Phone: sound rolls out from the phone.
    case echo
    /// Offline: the two sides drift apart, hollow.
    case resting

    /// One loop of the animation, in seconds.
    var period: Double {
        switch self {
        case .linked: return 2.4
        case .seeking: return 9.6
        case .handshake: return 1.8
        case .streaming: return 0.9
        case .mirroring: return 1.8
        case .ringing: return 1.5
        case .echo: return 1.4
        case .resting: return 4
        }
    }

    /// A representative still, for Reduce Motion.
    var stillTime: Double {
        switch self {
        case .linked: return 0.25
        case .seeking: return 0.9
        case .handshake: return 0.7
        case .streaming: return 0.2
        case .mirroring: return 1.25
        case .ringing: return 0.5
        case .echo: return 0.35
        case .resting: return 0
        }
    }
}

/// Droidtosh's status mark. Draws in the current foreground style, so it works on any surface,
/// and holds still when Reduce Motion is on.
struct TetherView: View {
    let state: TetherState
    var size: CGFloat = 28
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var onScreen = true

    /// Calm states hold a still frame, and small marks don't need more than 30 fps.
    private var isStill: Bool {
        switch state {
        case .linked, .resting: return true
        default: return false
        }
    }

    var body: some View {
        Group {
            if reduceMotion || isStill || !onScreen {
                canvas(time: state.stillTime)
            } else {
                TimelineView(.animation(minimumInterval: size <= 28 ? 1.0 / 30 : 1.0 / 60)) { timeline in
                    canvas(time: timeline.date.timeIntervalSinceReferenceDate)
                }
            }
        }
        .frame(width: size, height: size)
        .background(WindowVisibilityReader(onScreen: $onScreen))
        .accessibilityHidden(true)
    }

    private func canvas(time: Double) -> some View {
        Canvas { context, canvasSize in
            TetherRenderer(state: state, time: time, side: min(canvasSize.width, canvasSize.height)).draw(in: &context)
        }
    }
}

/// Reports whether the hosting window is actually visible (on screen and not occluded), so
/// animations stop in closed popovers and hidden panels.
struct WindowVisibilityReader: NSViewRepresentable {
    @Binding var onScreen: Bool

    func makeNSView(context: Context) -> TrackingView {
        let view = TrackingView()
        view.onChange = { visible in
            DispatchQueue.main.async { if onScreen != visible { onScreen = visible } }
        }
        return view
    }

    func updateNSView(_ view: TrackingView, context: Context) {}

    final class TrackingView: NSView {
        var onChange: ((Bool) -> Void)?
        private var observers: [NSObjectProtocol] = []

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            observers.forEach(NotificationCenter.default.removeObserver)
            observers = []
            guard let window else { return }
            observers.append(NotificationCenter.default.addObserver(
                forName: NSWindow.didChangeOcclusionStateNotification, object: window, queue: .main
            ) { [weak self] _ in MainActor.assumeIsolated { self?.report() } })
            report()
        }

        private func report() {
            guard let window else { return }
            onChange?(window.isVisible && window.occlusionState.contains(.visible))
        }

        deinit { observers.forEach(NotificationCenter.default.removeObserver) }
    }
}

/// Draws one frame. Coordinates follow a 64-point square, scaled to the view.
private struct TetherRenderer {
    let state: TetherState
    let time: Double
    let side: CGFloat

    private var scale: CGFloat { side / 64 }
    /// Strokes keep a readable width at every size, as `vector-effect: non-scaling-stroke` would.
    private var stroke: CGFloat { max(1, min(2, side / 24)) }

    private func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * scale, y: y * scale) }

    private func phase(_ period: Double, delay: Double = 0) -> Double {
        let value = (time - delay).truncatingRemainder(dividingBy: period) / period
        return value < 0 ? value + 1 : value
    }

    func draw(in context: inout GraphicsContext) {
        switch state {
        case .linked: drawLinked(&context)
        case .seeking: drawSeeking(&context)
        case .handshake: drawHandshake(&context)
        case .streaming(let toPhone): drawStreaming(&context, toPhone: toPhone)
        case .mirroring: drawMirroring(&context)
        case .ringing: drawRinging(&context)
        case .echo: drawEcho(&context)
        case .resting: drawResting(&context)
        }
    }

    // MARK: Shapes

    private func dot(_ context: inout GraphicsContext, _ x: CGFloat, _ y: CGFloat, radius: CGFloat, opacity: Double = 1) {
        let center = point(x, y)
        let r = radius * scale
        var layer = context
        layer.opacity = opacity
        layer.fill(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)), with: .foreground)
    }

    private func ring(_ context: inout GraphicsContext, _ x: CGFloat, _ y: CGFloat, radius: CGFloat, opacity: Double = 1, width: CGFloat? = nil, dash: [CGFloat] = []) {
        let center = point(x, y)
        let r = radius * scale
        var layer = context
        layer.opacity = opacity
        layer.stroke(
            Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)),
            with: .foreground,
            style: StrokeStyle(lineWidth: width ?? stroke, lineCap: .round, dash: dash.map { $0 * scale })
        )
    }

    private func line(_ context: inout GraphicsContext, from: CGPoint, to: CGPoint, opacity: Double, dash: [CGFloat] = []) {
        var path = Path()
        path.move(to: point(from.x, from.y))
        path.addLine(to: point(to.x, to.y))
        var layer = context
        layer.opacity = opacity
        layer.stroke(path, with: .foreground, style: StrokeStyle(lineWidth: max(1, stroke * 0.75), lineCap: .round, dash: dash.map { $0 * scale }))
    }

    // MARK: Easing

    /// The CSS ease-out keyframe "scale 1.4 at 0%, 1 from 18%".
    private func beat(_ p: Double) -> CGFloat {
        guard p < 0.18 else { return 1 }
        let t = p / 0.18
        return 1 + 0.4 * CGFloat(pow(1 - t, 2))
    }

    private func easeInOut(_ t: Double) -> Double {
        t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
    }

    private func easeOut(_ t: Double) -> Double { 1 - pow(1 - t, 2) }

    private func orbit(radius: CGFloat, angle: Double) -> CGPoint {
        CGPoint(x: 32 + radius * CGFloat(cos(angle)), y: 32 + radius * CGFloat(sin(angle)))
    }

    // MARK: States

    private func drawLinked(_ context: inout GraphicsContext) {
        let period = 2.4
        ring(&context, 32, 32, radius: 20, opacity: 0.22, width: max(1, stroke * 0.75))
        let tails: [(lag: Double, radius: CGFloat, opacity: Double)] = [(0.2, 1.6, 0.28), (0.1, 2.2, 0.55), (0, 3, 1)]
        for tail in tails {
            let position = orbit(radius: 20, angle: phase(period, delay: tail.lag) * 2 * .pi)
            dot(&context, position.x, position.y, radius: tail.radius, opacity: tail.opacity)
        }
        dot(&context, 12, 32, radius: 5.5 * beat(phase(period, delay: 1.2)))
        dot(&context, 52, 32, radius: 5.5 * beat(phase(period)))
    }

    private func drawSeeking(_ context: inout GraphicsContext) {
        for delay in [0.0, 1.2] {
            let p = phase(2.4, delay: delay)
            ring(&context, 32, 32, radius: 6 * CGFloat(1 + 3.4 * easeOut(p)), opacity: 0.55 * (1 - p))
        }
        ring(&context, 32, 32, radius: 22, opacity: 0.4, width: max(1, stroke * 0.75), dash: [2, 5])
        let position = orbit(radius: 22, angle: easeInOut(phase(3.2)) * 2 * .pi)
        dot(&context, position.x, position.y, radius: 4)
        dot(&context, 32, 32, radius: 5.5)
    }

    private func drawHandshake(_ context: inout GraphicsContext) {
        let p = phase(1.8)
        if p > 0.4 && p < 0.8 {
            let local = (p - 0.4) / 0.4
            let opacity = local < 0.3 ? 0.65 * local / 0.3 : 0.65 * (1 - (local - 0.3) / 0.7)
            ring(&context, 32, 32, radius: 14 * CGFloat(0.3 + local), opacity: opacity)
        }
        let angle = easeInOut(p) * 2 * .pi
        let inward = CGFloat(11 * (1 - cos(2 * .pi * p)) / 2)
        let left = orbit(radius: 20 - inward, angle: angle + .pi)
        let right = orbit(radius: 20 - inward, angle: angle)
        dot(&context, left.x, left.y, radius: 5)
        dot(&context, right.x, right.y, radius: 5)
    }

    private func drawStreaming(_ context: inout GraphicsContext, toPhone: Bool) {
        let spacing: CGFloat = 7
        let shift = CGFloat(phase(0.45)) * spacing
        var x: CGFloat = 18 - spacing
        while x <= 46 + spacing {
            let position = toPhone ? x + shift : x + spacing - shift
            if position >= 18 && position <= 46 {
                dot(&context, position, 32, radius: 1.75)
            }
            x += spacing
        }
        let pulse = beat(phase(0.9))
        // The Mac is on the left and the phone on the right; the receiving side beats.
        dot(&context, 10, 32, radius: 5.5 * (toPhone ? 1 : pulse))
        dot(&context, 54, 32, radius: 5.5 * (toPhone ? pulse : 1))
    }

    private func drawMirroring(_ context: inout GraphicsContext) {
        let p = phase(1.8)
        line(&context, from: CGPoint(x: 12, y: 32), to: CGPoint(x: 52, y: 32), opacity: 0.18)
        dot(&context, 12, 32, radius: 5.5)
        ring(&context, 52, 32, radius: 5)
        let fill: CGFloat
        switch p {
        case ..<0.54: fill = 0
        case ..<0.64: fill = CGFloat((p - 0.54) / 0.1) * 1.2
        case ..<0.72: fill = 1.2 - 0.2 * CGFloat((p - 0.64) / 0.08)
        case ..<0.88: fill = 1
        default: fill = 1 - CGFloat((p - 0.88) / 0.12)
        }
        if fill > 0 { dot(&context, 52, 32, radius: 5.5 * fill) }
        if p < 0.58 {
            let travel = CGFloat(easeInOut(min(p / 0.55, 1)))
            let opacity = p < 0.08 ? p / 0.08 : (p > 0.55 ? 1 - (p - 0.55) / 0.03 : 1)
            dot(&context, 12 + 40 * travel, 32, radius: 3, opacity: opacity)
        }
    }

    private func drawRinging(_ context: inout GraphicsContext) {
        for delay in [0.0, 0.18, 0.36] {
            let p = phase(1.5, delay: delay)
            guard p < 0.7 else { continue }
            let t = p / 0.7
            ring(&context, 32, 32, radius: 9 * CGFloat(1 + 2 * easeOut(t)), opacity: 0.7 * (1 - t))
        }
        let p = phase(1.5)
        var angle = 0.0
        if p < 0.45 {
            let envelope = min(p / 0.05, 1) * min((0.45 - p) / 0.05, 1)
            angle = 16 * cos(2 * .pi * p / 0.1 + .pi) * envelope
        }
        var phone = context
        let center = point(32, 32)
        phone.translateBy(x: center.x, y: center.y)
        phone.rotate(by: .degrees(angle))
        let rect = CGRect(x: -5.5 * scale, y: -11 * scale, width: 11 * scale, height: 22 * scale)
        phone.fill(Path(roundedRect: rect, cornerRadius: 3.5 * scale), with: .foreground)
    }

    private func drawEcho(_ context: inout GraphicsContext) {
        dot(&context, 16, 32, radius: 5.5 * beat(phase(1.4)))
        for (index, radius) in [CGFloat(12), 20, 28].enumerated() {
            let p = phase(1.4, delay: Double(index) * 0.2)
            let opacity: Double
            switch p {
            case ..<0.25: opacity = 0.12 + 0.88 * easeInOut(p / 0.25)
            case ..<0.55: opacity = 1 - 0.88 * easeInOut((p - 0.25) / 0.3)
            default: opacity = 0.12
            }
            var arc = Path()
            arc.addArc(center: point(16, 32), radius: radius * scale, startAngle: .degrees(-50), endAngle: .degrees(50), clockwise: false)
            var layer = context
            layer.opacity = opacity
            layer.stroke(arc, with: .foreground, style: StrokeStyle(lineWidth: stroke * 1.2, lineCap: .round))
        }
    }

    private func drawResting(_ context: inout GraphicsContext) {
        var layer = context
        layer.opacity = 0.6
        line(&layer, from: CGPoint(x: 19, y: 32), to: CGPoint(x: 45, y: 32), opacity: 0.5, dash: [2, 5])
        let a = CGFloat(-2.5 * cos(2 * .pi * phase(4)))
        let b = CGFloat(-2.5 * cos(2 * .pi * phase(4, delay: 2)))
        ring(&layer, 12, 32 + a, radius: 5)
        ring(&layer, 52, 32 + b, radius: 5)
    }
}

extension DroidtoshStatus {
    /// The Tether for this connection state.
    var tether: TetherState {
        switch self {
        case .connected: return .linked
        case .connecting: return .handshake
        case .offline: return .resting
        case .notPaired: return .seeking
        }
    }
}
