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
        Window("Pair Phone", id: "pairing") {
            PairingView(model: appDelegate.model)
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
        }
        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in WindowActivation.windowClosed() }
        }
    }
}
