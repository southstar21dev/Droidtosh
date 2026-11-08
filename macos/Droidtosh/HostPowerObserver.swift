import AppKit

final class HostPowerObserver {
    private let onSleep: () -> Void
    private let onWake: () -> Void

    init(onSleep: @escaping () -> Void, onWake: @escaping () -> Void) {
        self.onSleep = onSleep
        self.onWake = onWake
    }

    func start() {
        let center = NSWorkspace.shared.notificationCenter
        center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.onSleep()
        }
        center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.onWake()
        }
    }
}
