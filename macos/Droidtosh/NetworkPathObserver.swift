import Foundation
import Network

final class NetworkPathObserver {
    private let onChange: () -> Void
    private let monitor = NWPathMonitor()
    private var lastSignature: String?

    init(onChange: @escaping () -> Void) {
        self.onChange = onChange
    }

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let signature = Self.signature(of: path)
            defer { self.lastSignature = signature }
            // The first update only reports the current path.
            guard let last = self.lastSignature, last != signature, path.status == .satisfied else { return }
            self.onChange()
        }
        monitor.start(queue: .main)
    }

    func stop() {
        monitor.cancel()
    }

    private static func signature(of path: NWPath) -> String {
        let interfaces = path.availableInterfaces.map(\.name).joined(separator: ",")
        return "\(path.status)|\(interfaces)"
    }
}
