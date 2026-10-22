import Foundation
import DroidsyncProtocol

/// Timing from PROTOCOL-v2.md §9.
public enum FindPhoneLimits {
    /// No `FindPhoneState` this long after a request reached a connected phone: "didn't respond".
    public static let responseTimeout: TimeInterval = 15
    /// The phone rings for 60 s; without an end report this long after it started, assume it ended.
    public static let ringCeiling: TimeInterval = 75
    /// The phone ignores a `START` older than 90 s, so a request still waiting for a
    /// connection after that is given up.
    public static let queuedExpiry: TimeInterval = 90
}

/// How a ring ended, as the Mac shows it.
public enum FindPhoneEnd: Equatable, Sendable {
    case stoppedOnPhone
    case stoppedFromMac
    case timedOut
    /// The phone couldn't ring; the string is its `error` (`disabled`, `audio_failed`, `stale`, …).
    case failed(String)
    /// The request waited for a reconnect longer than the phone would accept it.
    case expired
    case unspecified

    public init(_ state: DSFindPhoneState) {
        switch state.ended {
        case .findEndStoppedOnPhone: self = .stoppedOnPhone
        case .findEndStoppedFromMac: self = .stoppedFromMac
        case .findEndTimeout: self = .timedOut
        case .findEndFailed: self = .failed(state.error)
        case .findEndUnspecified, .UNRECOGNIZED:
            self = state.error.isEmpty ? .unspecified : .failed(state.error)
        }
    }

    public var text: String {
        switch self {
        case .stoppedOnPhone: return "Stopped on the phone"
        case .stoppedFromMac: return "Stopped"
        case .timedOut: return "Rang for a minute — stopped"
        case .failed(let error): return FindPhoneEnd.describe(error: error)
        case .expired: return "The phone didn't reconnect in time"
        case .unspecified: return "Stopped ringing"
        }
    }

    /// Short identifier for logs and the e2e peer.
    public var code: String {
        switch self {
        case .stoppedOnPhone: return "stopped_on_phone"
        case .stoppedFromMac: return "stopped_from_mac"
        case .timedOut: return "timeout"
        case .failed: return "failed"
        case .expired: return "expired"
        case .unspecified: return "unspecified"
        }
    }

    public static func describe(error: String) -> String {
        switch error {
        case "disabled": return "Find my phone is turned off on the phone"
        case "audio_failed": return "The phone couldn't play the sound"
        case "stale": return "The request reached the phone too late — try again"
        default: return "The phone couldn't ring"
        }
    }
}

/// Find my phone from the Mac's side (§9): idle → requested → ringing → ended.
///
/// Not thread-safe: use it from one queue. Call `tick()` about once a second while a request
/// is outstanding; the clock is injected so the timeouts can be tested.
public final class FindPhoneSession {
    public enum Phase: Equatable, Sendable {
        case idle
        /// A `START` was sent. `waitingForConnection` while the phone is disconnected: the
        /// request sits in the reliable outbox and goes out on the next Resume.
        case requested(waitingForConnection: Bool)
        case ringing
        /// Connected, but no `FindPhoneState` within `responseTimeout`.
        case noResponse
        case ended(FindPhoneEnd)
    }

    public private(set) var phase: Phase = .idle
    /// The `request_id` of the current (or last) `START`.
    public private(set) var requestID: String?

    private let clock: () -> Date
    private let responseTimeout: TimeInterval
    private let makeID: () -> String
    private var connected = false
    private var createdAt: Date?
    /// When the request was last known to reach a connected phone; nil while queued.
    private var deliveredAt: Date?
    private var ringingSince: Date?

    public init(
        clock: @escaping () -> Date = Date.init,
        responseTimeout: TimeInterval = FindPhoneLimits.responseTimeout,
        makeID: @escaping () -> String = { UUID().uuidString }
    ) {
        self.clock = clock
        self.responseTimeout = responseTimeout
        self.makeID = makeID
    }

    /// Whether the main button should stop rather than start.
    public var canStop: Bool {
        switch phase {
        case .requested, .ringing: return true
        case .idle, .noResponse, .ended: return false
        }
    }

    public var buttonTitle: String { canStop ? "Stop Ringing" : "Find My Phone" }

    /// The line under the button, or nil when there is nothing to say.
    public var statusText: String? {
        switch phase {
        case .idle: return nil
        case .requested(waitingForConnection: true): return "Will ring when the phone reconnects"
        case .requested(waitingForConnection: false), .ringing: return "Ringing…"
        case .noResponse: return "Phone didn't respond"
        case .ended(let end): return end.text
        }
    }

    /// Short state name for logs and the e2e peer.
    public var phaseCode: String {
        switch phase {
        case .idle: return "idle"
        case .requested(waitingForConnection: true): return "waiting"
        case .requested(waitingForConnection: false): return "requested"
        case .ringing: return "ringing"
        case .noResponse: return "no_response"
        case .ended: return "ended"
        }
    }

    public func setConnected(_ isConnected: Bool) {
        guard isConnected != connected else { return }
        connected = isConnected
        guard case .requested = phase else { return }
        // The outbox retransmits the request after Resume; restart the response clock then.
        phase = .requested(waitingForConnection: !isConnected)
        deliveredAt = isConnected ? clock() : nil
    }

    /// Starts a ring; returns the request to send (reliably).
    public func start() -> DSFindPhoneRequest {
        let id = makeID()
        requestID = id
        let now = clock()
        createdAt = now
        deliveredAt = connected ? now : nil
        ringingSince = nil
        phase = .requested(waitingForConnection: !connected)
        return request(id: id, action: .start)
    }

    /// Stops the ring; returns the `STOP` to send, or nil when nothing is outstanding.
    /// The phone answers `ended:STOPPED_FROM_MAC`; the Mac shows "Stopped" right away.
    public func stop() -> DSFindPhoneRequest? {
        guard canStop else { return nil }
        phase = .ended(.stoppedFromMac)
        ringingSince = nil
        deliveredAt = nil
        return request(id: requestID ?? makeID(), action: .stop)
    }

    /// Applies a `FindPhoneState`. Reports for an older request are ignored while a newer one
    /// is outstanding; with nothing outstanding, a report is adopted (a ring the Mac lost
    /// track of, e.g. after a restart, can still be shown and stopped).
    @discardableResult
    public func receive(_ state: DSFindPhoneState) -> Bool {
        let outstanding = canStop
        if outstanding, let requestID, state.requestID != requestID { return false }
        if !outstanding, !state.ringing, state.requestID != requestID { return false }
        if !state.requestID.isEmpty { requestID = state.requestID }
        deliveredAt = nil
        if state.ringing {
            phase = .ringing
            ringingSince = clock()
        } else {
            phase = .ended(FindPhoneEnd(state))
            ringingSince = nil
        }
        return true
    }

    /// Runs the timeouts; returns true when the phase changed.
    @discardableResult
    public func tick() -> Bool {
        let now = clock()
        switch phase {
        case .requested(waitingForConnection: false):
            guard let deliveredAt, now.timeIntervalSince(deliveredAt) >= responseTimeout else { return false }
            phase = .noResponse
            return true
        case .requested(waitingForConnection: true):
            guard let createdAt, now.timeIntervalSince(createdAt) >= FindPhoneLimits.queuedExpiry else { return false }
            phase = .ended(.expired)
            return true
        case .ringing:
            guard let ringingSince, now.timeIntervalSince(ringingSince) >= FindPhoneLimits.ringCeiling else { return false }
            phase = .ended(.timedOut)
            return true
        case .idle, .noResponse, .ended:
            return false
        }
    }

    /// Forgets the current ring (unpair).
    public func reset() {
        phase = .idle
        requestID = nil
        createdAt = nil
        deliveredAt = nil
        ringingSince = nil
    }

    /// Whether `envelope` is the `START` of request `requestID`; with
    /// `RelayTransport.withdrawUnsent`, Stop takes back a `START` the phone never got
    /// instead of sending both on the next reconnect.
    public static func isStart(_ envelope: DSEnvelope, requestID: String) -> Bool {
        guard case .findPhoneRequest(let request)? = envelope.payload else { return false }
        return request.action == .start && request.requestID == requestID
    }

    private func request(id: String, action: DSFindAction) -> DSFindPhoneRequest {
        var request = DSFindPhoneRequest()
        request.requestID = id
        request.action = action
        return request
    }
}
