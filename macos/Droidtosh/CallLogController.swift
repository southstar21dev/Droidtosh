import Foundation
import DroidsyncKit

/// Owns the phone's call log as paged in from the phone.
@MainActor
final class CallLogController: ObservableObject {
    @Published private(set) var entries: [DSCallLogEntry] = []
    @Published private(set) var hasMore = false

    private let send: (DSEnvelope) -> Void

    init(send: @escaping (DSEnvelope) -> Void) {
        self.send = send
    }

    func handle(_ envelope: DSEnvelope) {
        guard case .calllogPage(let page)? = envelope.payload else { return }
        hasMore = page.hasMore_p
        var seen = Set(entries.map(\.id))
        for entry in page.entries where seen.insert(entry.id).inserted {
            entries.append(entry)
        }
    }

    func request(reset: Bool = false) {
        if reset {
            self.reset()
        }
        sendRequest()
    }

    func loadMore() {
        sendRequest()
    }

    func reset() {
        entries = []
        hasMore = false
    }

    private func sendRequest() {
        var request = DSCallLogRequest()
        request.offset = UInt32(entries.count)
        request.limit = 50
        send(protocolEnvelope(.calllogRequest(request)))
    }
}
