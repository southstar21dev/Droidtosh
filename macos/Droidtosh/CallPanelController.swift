import AppKit
import SwiftUI

/// Shows `CallPanelView` in a borderless floating panel at the top right of the screen, over
/// every Space and full-screen app, without taking focus from the frontmost app.
@MainActor
final class CallPanelController {
    private var panel: NSPanel?

    func update(model: AppModel, visible: Bool) {
        if visible {
            let panel = self.panel ?? makePanel(model: model)
            self.panel = panel
            if !panel.isVisible { position(panel) }
            panel.orderFrontRegardless()
        } else {
            panel?.orderOut(nil)
        }
    }

    private func makePanel(model: AppModel) -> NSPanel {
        let host = NSHostingView(rootView: CallPanelView(model: model))
        host.sizingOptions = [.intrinsicContentSize]
        let size = host.fittingSize
        let panel = CallPanel(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.nonactivatingPanel, .borderless],
            backing: .buffered,
            defer: false
        )
        panel.title = "Droidtosh Call"
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isMovableByWindowBackground = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.animationBehavior = .alertPanel
        panel.contentView = host
        position(panel)
        return panel
    }

    private func position(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let size = panel.frame.size
        panel.setFrameOrigin(NSPoint(
            x: visible.maxX - size.width - 16,
            y: visible.maxY - size.height - 12
        ))
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

/// A borderless panel still takes clicks on its buttons without activating the app.
private final class CallPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}
