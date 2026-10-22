import AppKit

@MainActor
enum WindowActivation {
    static func present() {
        if NSApp.activationPolicy() != .regular {
            NSApp.setActivationPolicy(.regular)
        }
        NSApp.activate()
    }

    static func windowClosed() {
        let hasVisibleWindow = NSApp.windows.contains { $0.isVisible && $0.canBecomeMain }
        if !hasVisibleWindow {
            NSApp.setActivationPolicy(.accessory)
        }
    }
}
