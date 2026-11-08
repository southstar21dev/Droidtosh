import AppKit
import Combine
import SwiftUI

/// The menu bar item and its popover (`MenuBarView`).
///
/// An `NSStatusItem` with an `NSPopover` rather than SwiftUI's `MenuBarExtra`: MenuBarExtra
/// doesn't expose its status item, so its button can't be made a drop target without digging
/// through private window classes. With our own status item, its button's window takes file
/// drags (NSWindow forwards dragging-destination calls to its delegate), so files and folders
/// dropped on the icon are sent just like ones dropped in the popover.
@MainActor
final class StatusItemController: NSObject, NSWindowDelegate, NSDraggingDestination {
    private let model: AppModel
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private var deviceObserver: AnyCancellable?

    init(model: AppModel) {
        self.model = model
        super.init()
        let host = NSHostingController(rootView: MenuBarView(model: model))
        host.sizingOptions = .preferredContentSize
        popover.contentViewController = host
        popover.behavior = .transient
        guard let button = statusItem.button else { return }
        button.target = self
        button.action = #selector(togglePopover(_:))
        button.imagePosition = .imageLeading
        button.window?.registerForDraggedTypes([.fileURL])
        button.window?.delegate = self
        // The label shows the phone's battery when that setting is on.
        deviceObserver = model.device.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.updateButton() }
        }
        updateButton()
    }

    private func updateButton() {
        guard let button = statusItem.button else { return }
        let image = NSImage(named: "MenuBarIcon")
        image?.isTemplate = true
        image?.accessibilityDescription = "Droidtosh"
        button.image = image
        button.font = .monospacedDigitSystemFont(ofSize: NSFont.menuBarFont(ofSize: 0).pointSize, weight: .regular)
        button.title = model.device.menuBarBatteryText.map { " \($0)" } ?? ""
    }

    @objc private func togglePopover(_ sender: Any?) {
        if popover.isShown {
            popover.performClose(sender)
            return
        }
        guard let button = statusItem.button else { return }
        model.notifications.refreshNotificationStatus()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        NSApp.activate()
        popover.contentViewController?.view.window?.makeKey()
    }

    // MARK: Drops on the status item

    private func accepts(_ sender: NSDraggingInfo) -> Bool {
        model.files.phoneSupportsFiles
            && sender.draggingPasteboard.canReadObject(forClasses: [NSURL.self], options: Self.fileURLsOnly)
    }

    private func setHighlighted(_ highlighted: Bool) {
        statusItem.button?.highlight(highlighted)
    }

    func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        let accepted = accepts(sender)
        setHighlighted(accepted)
        return accepted ? .copy : []
    }

    func draggingUpdated(_ sender: NSDraggingInfo) -> NSDragOperation {
        accepts(sender) ? .copy : []
    }

    func draggingExited(_ sender: NSDraggingInfo?) {
        setHighlighted(false)
    }

    func draggingEnded(_ sender: NSDraggingInfo) {
        setHighlighted(false)
    }

    func prepareForDragOperation(_ sender: NSDraggingInfo) -> Bool {
        accepts(sender)
    }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        setHighlighted(false)
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: Self.fileURLsOnly) as? [URL] ?? []
        guard model.files.phoneSupportsFiles, !urls.isEmpty else { return false }
        // Folders are zipped first, as for drops in the popover.
        model.files.send(urls: urls)
        return true
    }

    private static let fileURLsOnly: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
}
