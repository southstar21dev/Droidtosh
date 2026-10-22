import Foundation
import DroidsyncProtocol

/// Cheap, thread-safe counters and an event log for the Diagnostics view. The transport
/// records from its queue and event loop; the UI reads a `DiagnosticsSnapshot` copy.
/// One recorder lives for the whole process, across transport rebuilds.
public final class DiagnosticsRecorder: @unchecked Sendable {
    public static let eventCapacity = 20
    public static let rttSampleCapacity = 20

    private let lock = NSLock()
    private let clock: () -> Date
    private let launchedAt: Date
    private var connectAttempts = 0
    private var state = "Stopped"
    private var stateSince: Date
    private var sessionStartedAt: Date?
    private var session = TrafficCounters()
    private var total = TrafficCounters()
    private var rejected = RejectedFrames()
    private var clipboard = ClipboardTransfers()
    private var files = FileTransfers()
    private var device = DeviceActivity()
    private var rttSamples: [TimeInterval] = []
    private var events: [DiagnosticsEvent] = []

    public init(clock: @escaping () -> Date = Date.init) {
        self.clock = clock
        let now = clock()
        launchedAt = now
        stateSince = now
    }

    // MARK: Recording

    public func recordConnectAttempt() {
        lock.withLock { connectAttempts += 1 }
    }

    /// Records a transport state change; a change into a failure also lands in the event log.
    public func recordState(_ description: String, isError: Bool = false) {
        lock.withLock {
            guard description != state else { return }
            state = description
            stateSince = clock()
            appendEventLocked(isError ? .error : .state, description)
        }
    }

    public func recordEvent(_ kind: DiagnosticsEvent.Kind, _ message: String) {
        lock.withLock { appendEventLocked(kind, message) }
    }

    public func recordSessionStarted() {
        lock.withLock {
            sessionStartedAt = clock()
            session = TrafficCounters()
            rttSamples.removeAll()
            appendEventLocked(.state, "Phone session started")
        }
    }

    public func recordSessionEnded() {
        lock.withLock {
            guard sessionStartedAt != nil else { return }
            sessionStartedAt = nil
            appendEventLocked(.state, "Phone session ended")
        }
    }

    public func recordInbound(_ payload: DSEnvelope.OneOf_Payload?, bytes: Int) {
        let category = Self.category(for: payload, inbound: true)
        lock.withLock {
            session.addInbound(category, bytes: bytes)
            total.addInbound(category, bytes: bytes)
        }
    }

    public func recordOutbound(_ payload: DSEnvelope.OneOf_Payload?, bytes: Int) {
        let category = Self.category(for: payload, inbound: false)
        lock.withLock {
            session.addOutbound(category, bytes: bytes)
            total.addOutbound(category, bytes: bytes)
        }
    }

    public func recordRejectedFrame(_ reason: RejectedFrames.Reason, detail: String) {
        lock.withLock {
            rejected.add(reason)
            appendEventLocked(.protocolError, detail)
        }
    }

    /// Records a round trip from a Pong that echoes one of this Mac's pings.
    public func recordRoundTrip(sentAtMs: UInt64) {
        lock.withLock {
            let sent = Date(timeIntervalSince1970: TimeInterval(sentAtMs) / 1000)
            let rtt = clock().timeIntervalSince(sent)
            guard rtt >= 0, rtt < 600 else { return }
            rttSamples.append(rtt)
            if rttSamples.count > Self.rttSampleCapacity { rttSamples.removeFirst() }
        }
    }

    public func recordClipboardTransfer(_ outcome: ClipboardTransfers.Outcome) {
        lock.withLock { clipboard.add(outcome) }
    }

    public func recordFileTransfer(_ outcome: FileTransferOutcome) {
        lock.withLock { files.add(outcome) }
    }

    public func recordDeviceActivity(_ event: DeviceActivity.Event) {
        lock.withLock { device.add(event) }
    }

    // MARK: Reading

    public func snapshot() -> DiagnosticsSnapshot {
        lock.withLock {
            let now = clock()
            return DiagnosticsSnapshot(
                capturedAt: now,
                launchedAt: launchedAt,
                state: state,
                stateSince: stateSince,
                sessionStartedAt: sessionStartedAt,
                reconnects: max(connectAttempts - 1, 0),
                session: session,
                total: total,
                rejected: rejected,
                clipboard: clipboard,
                files: files,
                device: device,
                rtt: RoundTripStats(samples: rttSamples),
                events: events
            )
        }
    }

    // MARK: Private

    private func appendEventLocked(_ kind: DiagnosticsEvent.Kind, _ message: String) {
        events.append(DiagnosticsEvent(date: clock(), kind: kind, message: message))
        if events.count > Self.eventCapacity { events.removeFirst(events.count - Self.eventCapacity) }
    }

    /// The feature an envelope belongs to: inbound payloads by `EnvelopeRoute`, outbound
    /// ones by the capability they need, and handshake/keepalive as "transport".
    static func category(for payload: DSEnvelope.OneOf_Payload?, inbound: Bool) -> String {
        guard let payload else { return "transport" }
        if inbound, let feature = EnvelopeRoute.features(for: payload).first {
            switch feature {
            case .connection: return "transport"
            case .notifications: return "notifications"
            case .calls: return "calls"
            case .callLog: return "calllog"
            case .clipboard: return "clipboard"
            case .files: return "files"
            case .device: return "device"
            }
        }
        switch ProtocolV2.requiredCapability(for: payload) {
        case ProtocolV2.Capability.notifications?: return "notifications"
        case ProtocolV2.Capability.calls?: return "calls"
        case ProtocolV2.Capability.callLog?: return "calllog"
        case ProtocolV2.Capability.clipboardText?, ProtocolV2.Capability.clipboardImage?: return "clipboard"
        case ProtocolV2.Capability.files?: return "files"
        case ProtocolV2.Capability.phoneStatus?, ProtocolV2.Capability.find?: return "device"
        default: return "transport"
        }
    }
}

public struct DiagnosticsEvent: Equatable, Sendable {
    public enum Kind: String, Sendable {
        case state
        case error
        case authFailure = "auth"
        case certificate
        case protocolError = "protocol"
    }

    public let date: Date
    public let kind: Kind
    public let message: String
}

public struct TrafficCounters: Equatable, Sendable {
    public var envelopesIn = 0
    public var envelopesOut = 0
    public var bytesIn = 0
    public var bytesOut = 0
    /// Envelope counts per feature, in and out.
    public var byFeatureIn: [String: Int] = [:]
    public var byFeatureOut: [String: Int] = [:]

    public init() {}

    mutating func addInbound(_ category: String, bytes: Int) {
        envelopesIn += 1
        bytesIn += bytes
        byFeatureIn[category, default: 0] += 1
    }

    mutating func addOutbound(_ category: String, bytes: Int) {
        envelopesOut += 1
        bytesOut += bytes
        byFeatureOut[category, default: 0] += 1
    }
}

public struct RejectedFrames: Equatable, Sendable {
    public enum Reason: Sendable {
        case oversize
        case undecodable
        case wrongVersion
    }

    public var oversize = 0
    public var undecodable = 0
    public var wrongVersion = 0

    public var total: Int { oversize + undecodable + wrongVersion }

    mutating func add(_ reason: Reason) {
        switch reason {
        case .oversize: oversize += 1
        case .undecodable: undecodable += 1
        case .wrongVersion: wrongVersion += 1
        }
    }
}

public struct ClipboardTransfers: Equatable, Sendable {
    public enum Outcome: Sendable {
        case completed
        case rejected
        case timedOut
    }

    public var completed = 0
    public var rejected = 0
    public var timedOut = 0

    mutating func add(_ outcome: Outcome) {
        switch outcome {
        case .completed: completed += 1
        case .rejected: rejected += 1
        case .timedOut: timedOut += 1
        }
    }
}

public struct FileTransfers: Equatable, Sendable {
    public var sent = 0
    public var received = 0
    public var failed = 0
    public var resumed = 0

    public init(sent: Int = 0, received: Int = 0, failed: Int = 0, resumed: Int = 0) {
        self.sent = sent
        self.received = received
        self.failed = failed
        self.resumed = resumed
    }

    mutating func add(_ outcome: FileTransferOutcome) {
        switch outcome {
        case .sent: sent += 1
        case .received: received += 1
        case .failed: failed += 1
        case .resumed: resumed += 1
        }
    }
}

/// Phone status and Find my phone activity (§8, §9).
public struct DeviceActivity: Equatable, Sendable {
    public enum Event: Sendable {
        case statusReceived
        case findRequested
        case findRang
        case findFailed
        case findNoResponse
        case lowBatteryAlert
    }

    public var statusesReceived = 0
    public var findRequests = 0
    public var findRings = 0
    public var findFailures = 0
    public var findNoResponses = 0
    public var lowBatteryAlerts = 0

    public init() {}

    mutating func add(_ event: Event) {
        switch event {
        case .statusReceived: statusesReceived += 1
        case .findRequested: findRequests += 1
        case .findRang: findRings += 1
        case .findFailed: findFailures += 1
        case .findNoResponse: findNoResponses += 1
        case .lowBatteryAlert: lowBatteryAlerts += 1
        }
    }
}

public struct RoundTripStats: Equatable, Sendable {
    public let last: TimeInterval?
    public let average: TimeInterval?
    public let minimum: TimeInterval?
    public let maximum: TimeInterval?
    public let sampleCount: Int

    init(samples: [TimeInterval]) {
        last = samples.last
        average = samples.isEmpty ? nil : samples.reduce(0, +) / Double(samples.count)
        minimum = samples.min()
        maximum = samples.max()
        sampleCount = samples.count
    }
}

public struct DiagnosticsSnapshot: Equatable, Sendable {
    public let capturedAt: Date
    public let launchedAt: Date
    public let state: String
    public let stateSince: Date
    public let sessionStartedAt: Date?
    public let reconnects: Int
    public let session: TrafficCounters
    public let total: TrafficCounters
    public let rejected: RejectedFrames
    public let clipboard: ClipboardTransfers
    public let files: FileTransfers
    public let device: DeviceActivity
    public let rtt: RoundTripStats
    public let events: [DiagnosticsEvent]

    public var lastError: DiagnosticsEvent? { events.last { $0.kind != .state } }
}

/// Values the transport layer can't see, gathered by the app for the report.
public struct DiagnosticsContext: Equatable, Sendable {
    public var appVersion: String
    public var macOSVersion: String
    public var relayHost: String
    public var relayPort: String
    public var relayFingerprint: String?
    public var macFingerprint: String?
    public var negotiatedVersion: UInt32?
    public var capabilities: [String]
    public var reliability: ReliableStream.Counters
    public var outboxDepth: Int

    public init(
        appVersion: String,
        macOSVersion: String,
        relayHost: String,
        relayPort: String,
        relayFingerprint: String?,
        macFingerprint: String?,
        negotiatedVersion: UInt32?,
        capabilities: [String],
        reliability: ReliableStream.Counters,
        outboxDepth: Int
    ) {
        self.appVersion = appVersion
        self.macOSVersion = macOSVersion
        self.relayHost = relayHost
        self.relayPort = relayPort
        self.relayFingerprint = relayFingerprint
        self.macFingerprint = macFingerprint
        self.negotiatedVersion = negotiatedVersion
        self.capabilities = capabilities
        self.reliability = reliability
        self.outboxDepth = outboxDepth
    }
}

/// Keeps secrets out of anything the user copies or exports.
public enum DiagnosticsRedaction {
    /// The first 8 characters of a fingerprint or token, for recognising it without exposing it.
    public static func short(_ value: String?) -> String {
        guard let value, !value.isEmpty else { return "—" }
        return value.count <= 8 ? value : String(value.prefix(8)) + "…"
    }

    /// Replaces every occurrence of each secret (room token, PSKs, full fingerprints) with a
    /// short, labelled stand-in. Secrets shorter than 8 characters are ignored so ordinary
    /// words are never redacted.
    public static func redact(_ text: String, secrets: [String]) -> String {
        var result = text
        for secret in secrets.filter({ $0.count >= 8 }).sorted(by: { $0.count > $1.count }) {
            result = result.replacingOccurrences(of: secret, with: "[redacted \(secret.prefix(4))…]")
        }
        return result
    }
}

public enum DiagnosticsReport {
    /// A plain-text report with no secrets: fingerprints are shortened, and the room token
    /// and PSK never appear (neither is part of the snapshot or context).
    public static func render(_ snapshot: DiagnosticsSnapshot, context: DiagnosticsContext) -> String {
        var lines: [String] = []
        let iso = ISO8601DateFormatter()
        func time(_ date: Date) -> String { iso.string(from: date) }

        lines.append("Droidtosh diagnostics — \(time(snapshot.capturedAt))")
        lines.append("App \(context.appVersion) · macOS \(context.macOSVersion) · up \(duration(snapshot.capturedAt.timeIntervalSince(snapshot.launchedAt)))")
        lines.append("")
        lines.append("[Connection]")
        lines.append("State: \(snapshot.state) (since \(time(snapshot.stateSince)))")
        lines.append("Relay: \(context.relayHost.isEmpty ? "—" : context.relayHost):\(context.relayPort)")
        lines.append("Relay fingerprint: \(DiagnosticsRedaction.short(context.relayFingerprint))")
        lines.append("Mac fingerprint: \(DiagnosticsRedaction.short(context.macFingerprint))")
        if let version = context.negotiatedVersion {
            lines.append("Protocol: v\(version) · \(context.capabilities.joined(separator: ", "))")
        } else {
            lines.append("Protocol: no phone session")
        }
        if let started = snapshot.sessionStartedAt {
            lines.append("Session uptime: \(duration(snapshot.capturedAt.timeIntervalSince(started)))")
        } else {
            lines.append("Session uptime: —")
        }
        lines.append("Reconnects since launch: \(snapshot.reconnects)")
        if let error = snapshot.lastError {
            lines.append("Last error: \(time(error.date)) [\(error.kind.rawValue)] \(error.message)")
        } else {
            lines.append("Last error: none")
        }
        lines.append("")
        lines.append("[Round trip]")
        if let last = snapshot.rtt.last, let avg = snapshot.rtt.average,
           let min = snapshot.rtt.minimum, let max = snapshot.rtt.maximum {
            lines.append("Last \(ms(last)) · avg \(ms(avg)) · min \(ms(min)) · max \(ms(max)) (\(snapshot.rtt.sampleCount) samples)")
        } else {
            lines.append("No samples yet")
        }
        lines.append("")
        lines.append("[Traffic]")
        lines.append("Session: \(traffic(snapshot.session))")
        lines.append("Total: \(traffic(snapshot.total))")
        lines.append("By feature (session, in/out): \(features(snapshot.session))")
        lines.append("Rejected frames: \(snapshot.rejected.total) (oversize \(snapshot.rejected.oversize), undecodable \(snapshot.rejected.undecodable), wrong version \(snapshot.rejected.wrongVersion))")
        lines.append("")
        lines.append("[Reliability]")
        let r = context.reliability
        lines.append("Outbox: \(context.outboxDepth) · evicted \(r.evicted) · expired \(r.expired) · gaps \(r.gaps) · retransmits \(r.retransmits) · duplicates \(r.duplicates)")
        lines.append("")
        lines.append("[Clipboard]")
        lines.append("Image transfers: completed \(snapshot.clipboard.completed) · rejected \(snapshot.clipboard.rejected) · timed out \(snapshot.clipboard.timedOut)")
        lines.append("")
        lines.append("[Files]")
        lines.append("Transfers: sent \(snapshot.files.sent) · received \(snapshot.files.received) · failed \(snapshot.files.failed) · resumed \(snapshot.files.resumed)")
        lines.append("")
        lines.append("[Phone]")
        let d = snapshot.device
        lines.append("Status updates: \(d.statusesReceived) · low battery alerts \(d.lowBatteryAlerts)")
        lines.append("Find my phone: requests \(d.findRequests) · rang \(d.findRings) · failed \(d.findFailures) · no response \(d.findNoResponses)")
        lines.append("")
        lines.append("[Events]")
        if snapshot.events.isEmpty {
            lines.append("none")
        } else {
            for event in snapshot.events.reversed() {
                lines.append("\(time(event.date)) [\(event.kind.rawValue)] \(event.message)")
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }

    public static func duration(_ interval: TimeInterval) -> String {
        let seconds = max(Int(interval), 0)
        if seconds < 60 { return "\(seconds)s" }
        if seconds < 3600 { return "\(seconds / 60)m \(seconds % 60)s" }
        return "\(seconds / 3600)h \((seconds % 3600) / 60)m"
    }

    public static func ms(_ interval: TimeInterval) -> String {
        "\(Int((interval * 1000).rounded())) ms"
    }

    public static func bytes(_ count: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(count), countStyle: .binary)
    }

    private static func traffic(_ counters: TrafficCounters) -> String {
        "in \(counters.envelopesIn) (\(bytes(counters.bytesIn))) · out \(counters.envelopesOut) (\(bytes(counters.bytesOut)))"
    }

    private static func features(_ counters: TrafficCounters) -> String {
        let names = Set(counters.byFeatureIn.keys).union(counters.byFeatureOut.keys).sorted()
        guard !names.isEmpty else { return "—" }
        return names.map { "\($0) \(counters.byFeatureIn[$0] ?? 0)/\(counters.byFeatureOut[$0] ?? 0)" }
            .joined(separator: ", ")
    }
}
