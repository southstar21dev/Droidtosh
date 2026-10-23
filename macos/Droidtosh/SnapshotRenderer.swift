#if DEBUG
import AppKit
import SwiftUI
import DroidsyncKit

/// Debug only: `Droidtosh --render-snapshots <dir>` renders every screen with fake state, in
/// light and dark, to PNGs and quits. Nothing connects, and no settings or stored secrets are
/// written: fixture settings go through the volatile argument domain.
///
/// Windows are placed off screen and captured from the window server. They have no desktop
/// behind them, so the popover and call panel are drawn over a stand-in wallpaper and
/// behind-window materials and glass may look flatter than on screen.
@MainActor
enum SnapshotRenderer {
    /// True while snapshots render: views skip live refreshes and nothing touches the stored secrets.
    nonisolated(unsafe) static var isActive = CommandLine.arguments.contains("--render-snapshots")

    static func run(outputDirectory: URL) async {
        try? FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
        NSApp.setActivationPolicy(.accessory)
        // Active, so controls draw in their key-window colours.
        NSApp.activate(ignoringOtherApps: true)

        let connected = makeModel(relayHost: "relay.example.com", fingerprint: fakeFingerprint)
        Fixtures.connected(connected)
        let setup = makeModel(relayHost: "relay.example.com", fingerprint: "")
        Fixtures.unpaired(setup)
        let unconfigured = makeModel(relayHost: "", fingerprint: "")
        Fixtures.unconfigured(unconfigured)
        let ringing = makeModel(relayHost: "relay.example.com", fingerprint: fakeFingerprint)
        Fixtures.connected(ringing)
        Fixtures.ringing(ringing)
        let inCall = makeModel(relayHost: "relay.example.com", fingerprint: fakeFingerprint)
        Fixtures.connected(inCall)
        Fixtures.inCall(inCall)

        for appearance in [NSAppearance.Name.aqua, .darkAqua] {
            let suffix = appearance == .aqua ? "light" : "dark"
            func save(_ name: String, _ image: NSBitmapImageRep?) {
                guard let data = image?.representation(using: .png, properties: [:]) else {
                    print("snapshot: failed \(name)")
                    return
                }
                let url = outputDirectory.appendingPathComponent("\(name)-\(suffix).png")
                try? data.write(to: url)
                print("snapshot: \(url.path)")
            }
            let windowSize = NSSize(width: 1040, height: 780)
            for section in MainSection.allCases {
                save("main-\(section.rawValue)", await window(MainView(model: connected, initialSection: section), size: windowSize, title: "Droidtosh", appearance: appearance))
            }
            save("main-overview-setup", await window(MainView(model: setup, initialSection: .overview), size: windowSize, title: "Droidtosh", appearance: appearance))
            save("main-notifications-empty", await window(MainView(model: unconfigured, initialSection: .notifications), size: NSSize(width: 900, height: 560), title: "Droidtosh", appearance: appearance))
            save("pairing", await window(PairingView(model: connected, onDone: {}), size: nil, title: "Pair Phone", appearance: appearance))
            save("pairing-unconfigured", await window(PairingView(model: unconfigured, onDone: {}), size: nil, title: "Pair Phone", appearance: appearance))
            for tab in SettingsTab.allCases {
                save("settings-\(tab.rawValue)", await window(SettingsPane(model: connected, tab: tab), size: nil, title: tab.title, appearance: appearance))
            }
            save("popover", await floating(MenuBarView(model: connected), appearance: appearance, cornerRadius: 14))
            save("popover-setup", await floating(MenuBarView(model: unconfigured), appearance: appearance, cornerRadius: 14))
            save("call-ringing", await floating(CallPanelView(model: ringing), appearance: appearance, cornerRadius: nil))
            save("call-active", await floating(CallPanelView(model: inCall), appearance: appearance, cornerRadius: nil))
        }
    }

    static let fakeFingerprint = "9f3c2a7be14d58c0a6f21e9d4b7c3a85e0d1f62b9a4c7e35d8b06f1a2c9e4d73"

    private static func makeModel(relayHost: String, fingerprint: String) -> AppModel {
        UserDefaults.standard.setVolatileDomain([
            "relayHost": relayHost,
            "relayPortText": "7443",
            "relayFingerprint": fingerprint,
            "clipboard-enabled": true,
            "files-receive-enabled": true,
            "device-battery-in-menu-bar": true,
            "device-low-battery-alerts": true,
            "launch-at-login": true,
            "device-find-supported": !relayHost.isEmpty && !fingerprint.isEmpty,
        ], forName: UserDefaults.argumentDomain)
        return AppModel()
    }

    // MARK: Rendering

    /// A titled window with the view as content, captured with its title bar and toolbar.
    private static func window(_ view: some View, size: NSSize?, title: String, appearance: NSAppearance.Name) async -> NSBitmapImageRep? {
        let host = NSHostingController(rootView: view)
        let window = SnapshotWindow(
            contentRect: NSRect(origin: .zero, size: size ?? NSSize(width: 600, height: 400)),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = title
        window.appearance = NSAppearance(named: appearance)
        window.isReleasedWhenClosed = false
        window.contentViewController = host
        window.setContentSize(size ?? host.view.fittingSize)
        // On screen (so lists and sidebars draw) but fully transparent.
        window.setFrameOrigin(NSPoint(x: -20_000, y: -20_000))
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        await settle()
        let image = windowImage(window) ?? capture(window.contentView?.superview ?? window.contentView!, background: .windowBackgroundColor, appearance: appearance)
        window.orderOut(nil)
        return image
    }

    /// A popover or panel over a stand-in wallpaper.
    private static func floating(_ view: some View, appearance: NSAppearance.Name, cornerRadius: CGFloat?) async -> NSBitmapImageRep? {
        let content = SnapshotBackdrop(cornerRadius: cornerRadius) { view }
        let host = NSHostingView(rootView: content)
        host.appearance = NSAppearance(named: appearance)
        let size = host.fittingSize
        host.frame = NSRect(origin: .zero, size: size)
        let window = SnapshotWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: appearance)
        window.isReleasedWhenClosed = false
        window.contentView = host
        // On screen (so lists and sidebars draw) but fully transparent.
        window.setFrameOrigin(NSPoint(x: -20_000, y: -20_000))
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        await settle()
        let image = windowImage(window) ?? capture(host, background: nil, appearance: appearance)
        window.orderOut(nil)
        return image
    }

    private static func settle() async {
        for _ in 0..<6 {
            try? await Task.sleep(nanoseconds: 120_000_000)
        }
    }

    private typealias WindowListCreateImage = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?

    /// The window as the window server composites it, which includes materials, glass and
    /// sidebars that `cacheDisplay` can't draw. Capturing the app's own window needs no Screen
    /// Recording permission. Looked up at runtime because the SDK marks it obsolete.
    private static func windowImage(_ window: NSWindow) -> NSBitmapImageRep? {
        guard let symbol = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "CGWindowListCreateImage") else { return nil }
        let create = unsafeBitCast(symbol, to: WindowListCreateImage.self)
        // .optionIncludingWindow, and .boundsIgnoreFraming | .bestResolution.
        guard let image = create(.null, 1 << 3, UInt32(window.windowNumber), (1 << 0) | (1 << 3))?.takeRetainedValue(),
              image.width > 1 else { return nil }
        return NSBitmapImageRep(cgImage: image)
    }

    private static func capture(_ view: NSView, background: NSColor?, appearance: NSAppearance.Name) -> NSBitmapImageRep? {
        view.layoutSubtreeIfNeeded()
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return nil }
        if let background {
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
            NSAppearance(named: appearance)?.performAsCurrentDrawingAppearance {
                background.setFill()
                view.bounds.fill()
            }
            NSGraphicsContext.restoreGraphicsState()
        }
        view.cacheDisplay(in: view.bounds, to: rep)
        return rep
    }
}

/// Draws as the key, main window so controls show their active (accent) look.
private final class SnapshotWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var isKeyWindow: Bool { true }
    override var isMainWindow: Bool { true }
}

/// A desktop-like backdrop so floating surfaces have something to sit on.
private struct SnapshotBackdrop<Content: View>: View {
    let cornerRadius: CGFloat?
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Group {
            if let cornerRadius {
                content
                    .background(.background, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
                    .compositingGroup()
                    .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
            } else {
                content
                    .compositingGroup()
                    .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
            }
        }
        .padding(36)
        .background(
            LinearGradient(
                colors: colorScheme == .dark
                    ? [Color(hex: 0x2A2320), Color(hex: 0x4A3F33)]
                    : [Color(hex: 0xF5ECD7), Color(hex: 0xD8C8A4)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
    }
}

// MARK: Fixtures

@MainActor
private enum Fixtures {
    static let phone = "Pixel 9 Pro"

    static func connected(_ model: AppModel) {
        let capabilities = ProtocolV2.macCapabilities
        model.connection.applySnapshotFixture(device: phone, capabilities: capabilities, macName: "Ada’s MacBook Pro", macModel: "MacBook Pro (14-inch, 2024)", status: "Connected")
        model.pairing.applySnapshotFixture(pairingURI: "droidtosh://pair?v=2&host=relay.example.com&port=7443&room=snapshot-room-token-0000&fp=\(SnapshotRenderer.fakeFingerprint)&psk=c25hcHNob3QtcHNrLW5vdC1yZWFs", paired: true)
        model.device.phoneConnected(capabilities: capabilities)
        var status = DSDeviceStatus()
        status.batteryPercent = 76
        status.charging = false
        status.networkType = .wifi
        status.wifiLevel = 3
        status.cellularLevel = 4
        status.cellularGeneration = "5G"
        status.carrier = "Lagoon Mobile"
        status.ringerMode = .vibrate
        status.sampledAtMs = UInt64(Date().timeIntervalSince1970 * 1000)
        model.device.handle(protocolEnvelope(.deviceStatus(status)))
        model.notifications.applySnapshotFixture(apps: apps, muted: ["com.google.android.youtube", "com.instagram.android", "com.spotify.music"], authorized: true, relayEnabled: true)
        var sim1 = DSPhoneAccount()
        sim1.id = "sim1"
        sim1.label = "Personal"
        var sim2 = DSPhoneAccount()
        sim2.id = "sim2"
        sim2.label = "Work"
        model.calls.applySnapshotFixture(activeCall: nil, accounts: [sim1, sim2])
        var page = DSCallLogPage()
        page.entries = callLog
        page.hasMore_p = true
        model.callLog.handle(protocolEnvelope(.calllogPage(page)))
        model.files.applySnapshotFixture(transfers: transfers)
        recordDiagnostics(model)
    }

    static func unpaired(_ model: AppModel) {
        model.connection.applySnapshotFixture(device: nil, capabilities: [], macName: "Ada’s MacBook Pro", macModel: "MacBook Pro (14-inch, 2024)", status: "Waiting for phone")
        model.pairing.applySnapshotFixture(pairingURI: nil, paired: false)
        model.notifications.applySnapshotFixture(apps: [], muted: [], authorized: nil, relayEnabled: true)
    }

    static func unconfigured(_ model: AppModel) {
        model.connection.applySnapshotFixture(device: nil, capabilities: [], macName: "Ada’s MacBook Pro", macModel: "MacBook Pro (14-inch, 2024)", status: "Relay not configured")
        model.pairing.applySnapshotFixture(pairingURI: nil, paired: false)
        model.notifications.applySnapshotFixture(apps: [], muted: [], authorized: false, relayEnabled: true)
    }

    static func ringing(_ model: AppModel) {
        model.calls.applySnapshotFixture(activeCall: call(
            id: "call-1", kind: .cellular, package: "", direction: .incoming, phase: .ringing,
            number: "+44 20 7946 0958", name: "Grace Hopper", sim: "Personal"
        ), accounts: [])
    }

    static func inCall(_ model: AppModel) {
        model.calls.applySnapshotFixture(activeCall: call(
            id: "call-2", kind: .voip, package: "com.whatsapp", direction: .outgoing, phase: .active,
            number: "", name: "Alan Turing", sim: ""
        ), accounts: [])
    }

    private static func call(id: String, kind: DSCallKind, package: String, direction: DSCallDirection, phase: DSCallPhase, number: String, name: String, sim: String) -> CallSnapshot? {
        var state = DSCallState()
        state.callID = id
        state.kind = kind
        state.packageName = package
        state.direction = direction
        state.phase = phase
        state.number = number
        state.contactName = name
        state.simLabel = sim
        let center = CallCenter()
        center.apply(state)
        return center.activeCall
    }

    static var apps: [DSAppInfo] {
        [
            ("com.whatsapp", "WhatsApp"), ("org.telegram.messenger", "Telegram"), ("com.google.android.gm", "Gmail"),
            ("com.google.android.calendar", "Calendar"), ("com.instagram.android", "Instagram"),
            ("com.google.android.youtube", "YouTube"), ("com.slack", "Slack"), ("com.spotify.music", "Spotify"),
            ("org.thoughtcrime.securesms", "Signal"), ("com.google.android.apps.messaging", "Messages"),
            ("com.revolut.revolut", "Revolut"), ("com.ubercab", "Uber"),
        ].map { package, label in
            var app = DSAppInfo()
            app.packageName = package
            app.label = label
            return app
        }
    }

    static var callLog: [DSCallLogEntry] {
        let now = Date()
        let calendar = Calendar.current
        func entry(_ id: String, _ name: String, _ number: String, _ type: DSCallLogType, daysAgo: Int, hour: Int, minute: Int, duration: UInt32, sim: String) -> DSCallLogEntry {
            var entry = DSCallLogEntry()
            entry.id = id
            entry.contactName = name
            entry.number = number
            entry.type = type
            let day = calendar.date(byAdding: .day, value: -daysAgo, to: now)!
            let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)!
            entry.startedAtMs = UInt64(min(date, now.addingTimeInterval(-600)).timeIntervalSince1970 * 1000)
            entry.durationS = duration
            entry.simLabel = sim
            return entry
        }
        return [
            entry("1", "Grace Hopper", "+44 20 7946 0958", .incoming, daysAgo: 0, hour: 9, minute: 41, duration: 754, sim: "Personal"),
            entry("2", "", "+1 415 555 0132", .missed, daysAgo: 0, hour: 8, minute: 15, duration: 0, sim: "Work"),
            entry("3", "Katherine Johnson", "+1 757 555 0199", .outgoing, daysAgo: 1, hour: 18, minute: 2, duration: 1260, sim: "Personal"),
            entry("4", "Alan Turing", "+44 161 496 0000", .rejected, daysAgo: 1, hour: 12, minute: 30, duration: 0, sim: "Personal"),
            entry("5", "Dentist", "+44 20 7946 0123", .incoming, daysAgo: 1, hour: 10, minute: 5, duration: 95, sim: "Personal"),
            entry("6", "Margaret Hamilton", "+1 617 555 0144", .outgoing, daysAgo: 3, hour: 16, minute: 48, duration: 312, sim: "Work"),
            entry("7", "Grace Hopper", "+44 20 7946 0958", .missed, daysAgo: 3, hour: 9, minute: 12, duration: 0, sim: "Personal"),
        ]
    }

    static var transfers: [FileTransferSnapshot] {
        let downloads = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
        return [
            FileTransferSnapshot(id: "t1", direction: .outgoing, name: "Quarterly report.pdf", size: 8_400_000, transferred: 5_100_000, phase: .transferring, savedName: nil, localURL: nil),
            FileTransferSnapshot(id: "t2", direction: .outgoing, name: "Holiday photos.zip", size: 0, transferred: 0, phase: .compressing, savedName: nil, localURL: nil),
            FileTransferSnapshot(id: "t3", direction: .incoming, name: "PXL_20261016_174512.jpg", size: 3_200_000, transferred: 3_200_000, phase: .completed, savedName: "PXL_20261016_174512.jpg", localURL: downloads),
            FileTransferSnapshot(id: "t4", direction: .outgoing, name: "Boarding pass.pkpass", size: 120_000, transferred: 120_000, phase: .completed, savedName: "Boarding pass.pkpass", localURL: nil),
            FileTransferSnapshot(id: "t5", direction: .incoming, name: "Screen recording.mp4", size: 64_000_000, transferred: 12_000_000, phase: .failed("disk_full"), savedName: nil, localURL: nil),
        ]
    }

    static func recordDiagnostics(_ model: AppModel) {
        let recorder = model.connection.diagnostics
        recorder.recordConnectAttempt()
        recorder.recordState("Connecting to relay")
        recorder.recordState("Waiting for phone")
        recorder.recordEvent(.state, "Relay fingerprint learned on first connect")
        recorder.recordState("Connected")
        recorder.recordSessionStarted()
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        for delay in [42, 38, 51, 47] as [UInt64] { recorder.recordRoundTrip(sentAtMs: now - delay) }
        var status = DSDeviceStatus()
        status.batteryPercent = 76
        for _ in 0..<12 { recorder.recordInbound(.deviceStatus(status), bytes: 96) }
        for _ in 0..<5 { recorder.recordOutbound(.deviceStatus(status), bytes: 64) }
        model.diagnostics.snapshotContext = DiagnosticsContext(
            appVersion: BuildConfig.version,
            macOSVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            relayHost: "relay.example.com",
            relayPort: "7443",
            relayFingerprint: SnapshotRenderer.fakeFingerprint,
            macFingerprint: nil,
            negotiatedVersion: 2,
            capabilities: ProtocolV2.macCapabilities,
            reliability: model.connection.reliabilityCounters,
            outboxDepth: 0
        )
    }
}
#endif
