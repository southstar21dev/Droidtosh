import AppKit
import SwiftUI

@main
struct DroidtoshApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    // The menu bar item is an NSStatusItem (see StatusItemController), so these windows open only
    // when asked for, not at launch.
    var body: some Scene {
        Window("Droidtosh", id: "main") {
            MainView(model: appDelegate.model)
        }
        .defaultLaunchBehavior(.suppressed)
        .defaultSize(width: 920, height: 640)
        .windowToolbarStyle(.unified)
        Window("Set Up Droidtosh", id: "pairing") {
            SetupView(model: appDelegate.model)
        }
        .defaultLaunchBehavior(.suppressed)
        .windowResizability(.contentSize)
        Settings {
            SettingsView(model: appDelegate.model)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    @MainActor let model = AppModel()
    @MainActor private var statusItem: StatusItemController?
    @MainActor private var servicesProvider: ServicesProvider?

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if DEBUG
        if let index = CommandLine.arguments.firstIndex(of: "--render-snapshots") {
            let directory = CommandLine.arguments.indices.contains(index + 1) ? CommandLine.arguments[index + 1] : "snapshots"
            Task { @MainActor in
                await SnapshotRenderer.run(outputDirectory: URL(fileURLWithPath: directory))
                NSApp.terminate(nil)
            }
            return
        }
        #endif
        Task { @MainActor in
            statusItem = StatusItemController(model: model)
            model.start()
            // The "Send to Phone" services declared in Info.plist call this object's methods.
            let provider = ServicesProvider(link: model.link)
            servicesProvider = provider
            NSApp.servicesProvider = provider
        }
        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in WindowActivation.windowClosed() }
        }
    }

    /// `tel:` links, for which Droidtosh is registered in Info.plist. Anything else is ignored.
    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls where url.scheme?.lowercased() == "tel" {
            Task { @MainActor in model.link.handleTel(url) }
        }
    }
}
