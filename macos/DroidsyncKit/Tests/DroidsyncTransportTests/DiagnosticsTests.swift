import XCTest
import DroidsyncProtocol
@testable import DroidsyncTransport

final class DiagnosticsTests: XCTestCase {
    private var now = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeRecorder() -> DiagnosticsRecorder {
        DiagnosticsRecorder(clock: { [unowned self] in self.now })
    }

    func testEventLogKeepsOnlyTheNewestEntries() {
        let recorder = makeRecorder()
        for index in 0..<(DiagnosticsRecorder.eventCapacity + 5) {
            recorder.recordEvent(.protocolError, "event \(index)")
        }
        let events = recorder.snapshot().events
        XCTAssertEqual(events.count, DiagnosticsRecorder.eventCapacity)
        XCTAssertEqual(events.first?.message, "event 5")
        XCTAssertEqual(events.last?.message, "event \(DiagnosticsRecorder.eventCapacity + 4)")
    }

    func testStateChangesAreLoggedOnceAndFailuresCountAsErrors() {
        let recorder = makeRecorder()
        recorder.recordState("Connecting to relay")
        recorder.recordState("Connecting to relay")
        now += 5
        recorder.recordState("Failed: relay connection closed", isError: true)
        let snapshot = recorder.snapshot()
        XCTAssertEqual(snapshot.events.map(\.message), ["Connecting to relay", "Failed: relay connection closed"])
        XCTAssertEqual(snapshot.state, "Failed: relay connection closed")
        XCTAssertEqual(snapshot.stateSince, now)
        XCTAssertEqual(snapshot.lastError?.kind, .error)
    }

    func testReconnectsCountAttemptsAfterTheFirst() {
        let recorder = makeRecorder()
        XCTAssertEqual(recorder.snapshot().reconnects, 0)
        recorder.recordConnectAttempt()
        XCTAssertEqual(recorder.snapshot().reconnects, 0)
        recorder.recordConnectAttempt()
        recorder.recordConnectAttempt()
        XCTAssertEqual(recorder.snapshot().reconnects, 2)
    }

    func testTrafficIsCountedPerSessionAndInTotalByFeature() {
        let recorder = makeRecorder()
        recorder.recordInbound(.notificationPosted(DSNotificationPosted()), bytes: 100)
        recorder.recordSessionStarted()
        recorder.recordInbound(.clipboardText(DSClipboardText()), bytes: 40)
        recorder.recordOutbound(.callAction(DSCallAction()), bytes: 30)
        recorder.recordOutbound(.ping(DSPing()), bytes: 10)
        let snapshot = recorder.snapshot()
        XCTAssertEqual(snapshot.session.envelopesIn, 1)
        XCTAssertEqual(snapshot.session.bytesIn, 40)
        XCTAssertEqual(snapshot.session.envelopesOut, 2)
        XCTAssertEqual(snapshot.session.byFeatureIn, ["clipboard": 1])
        XCTAssertEqual(snapshot.session.byFeatureOut, ["calls": 1, "transport": 1])
        XCTAssertEqual(snapshot.total.envelopesIn, 2)
        XCTAssertEqual(snapshot.total.bytesIn, 140)
    }

    func testSessionTimesAndRoundTripStats() {
        let recorder = makeRecorder()
        recorder.recordSessionStarted()
        let startedAt = now
        let sentMs = UInt64(now.timeIntervalSince1970 * 1000)
        now += 0.040
        recorder.recordRoundTrip(sentAtMs: sentMs)
        now += 0.080
        recorder.recordRoundTrip(sentAtMs: sentMs)
        let stats = recorder.snapshot().rtt
        XCTAssertEqual(stats.sampleCount, 2)
        XCTAssertEqual(stats.last ?? 0, 0.120, accuracy: 0.001)
        XCTAssertEqual(stats.minimum ?? 0, 0.040, accuracy: 0.001)
        XCTAssertEqual(stats.average ?? 0, 0.080, accuracy: 0.001)
        XCTAssertEqual(recorder.snapshot().sessionStartedAt, startedAt)
        recorder.recordSessionEnded()
        XCTAssertNil(recorder.snapshot().sessionStartedAt)
    }

    func testRoundTripIgnoresTimestampsFromTheFuture() {
        let recorder = makeRecorder()
        recorder.recordRoundTrip(sentAtMs: UInt64((now.timeIntervalSince1970 + 10) * 1000))
        XCTAssertEqual(recorder.snapshot().rtt.sampleCount, 0)
    }

    func testRejectedFramesAndClipboardOutcomes() {
        let recorder = makeRecorder()
        recorder.recordRejectedFrame(.oversize, detail: "too big")
        recorder.recordRejectedFrame(.wrongVersion, detail: "v1")
        recorder.recordClipboardTransfer(.completed)
        recorder.recordClipboardTransfer(.timedOut)
        let snapshot = recorder.snapshot()
        XCTAssertEqual(snapshot.rejected.total, 2)
        XCTAssertEqual(snapshot.rejected.wrongVersion, 1)
        XCTAssertEqual(snapshot.clipboard, ClipboardTransfers(completed: 1, rejected: 0, timedOut: 1))
        recorder.recordFileTransfer(.sent)
        recorder.recordFileTransfer(.resumed)
        recorder.recordFileTransfer(.failed)
        XCTAssertEqual(recorder.snapshot().files, FileTransfers(sent: 1, received: 0, failed: 1, resumed: 1))
        recorder.recordDeviceActivity(.statusReceived)
        recorder.recordDeviceActivity(.statusReceived)
        recorder.recordDeviceActivity(.findRequested)
        recorder.recordDeviceActivity(.findNoResponse)
        recorder.recordDeviceActivity(.lowBatteryAlert)
        let device = recorder.snapshot().device
        XCTAssertEqual(device.statusesReceived, 2)
        XCTAssertEqual(device.findRequests, 1)
        XCTAssertEqual(device.findNoResponses, 1)
        XCTAssertEqual(device.lowBatteryAlerts, 1)
        XCTAssertEqual(device.findRings, 0)
        XCTAssertEqual(snapshot.lastError?.message, "v1")
    }

    func testDeviceEnvelopesAreCountedAsDevice() {
        XCTAssertEqual(DiagnosticsRecorder.category(for: .deviceStatus(DSDeviceStatus()), inbound: true), "device")
        XCTAssertEqual(DiagnosticsRecorder.category(for: .findPhoneState(DSFindPhoneState()), inbound: true), "device")
        XCTAssertEqual(DiagnosticsRecorder.category(for: .findPhoneRequest(DSFindPhoneRequest()), inbound: false), "device")
    }

    func testRedactionShortensAndReplacesSecrets() {
        let fingerprint = String(repeating: "ab", count: 32)
        XCTAssertEqual(DiagnosticsRedaction.short(fingerprint), "abababab…")
        XCTAssertEqual(DiagnosticsRedaction.short(nil), "—")
        let room = "RoomTokenRoomTokenRoomTokenRoomTokenRoomTok"
        let log = "join room=\(room) fp=\(fingerprint) ok"
        let redacted = DiagnosticsRedaction.redact(log, secrets: [room, fingerprint, "short"])
        XCTAssertFalse(redacted.contains(room))
        XCTAssertFalse(redacted.contains(fingerprint))
        XCTAssertTrue(redacted.contains("[redacted Room…]"))
        XCTAssertTrue(redacted.hasSuffix(" ok"))
    }

    func testReportContainsSectionsAndNoFullFingerprints() {
        let recorder = makeRecorder()
        recorder.recordState("Connected")
        recorder.recordSessionStarted()
        let relayFingerprint = String(repeating: "c", count: 64)
        let macFingerprint = String(repeating: "d", count: 64)
        let context = DiagnosticsContext(
            appVersion: "0.9.0",
            macOSVersion: "15.0",
            relayHost: "relay.example",
            relayPort: "7443",
            relayFingerprint: relayFingerprint,
            macFingerprint: macFingerprint,
            negotiatedVersion: 2,
            capabilities: ["notifications", "reliable"],
            reliability: ReliableStream.Counters(),
            outboxDepth: 3
        )
        let report = DiagnosticsReport.render(recorder.snapshot(), context: context)
        for section in ["[Connection]", "[Round trip]", "[Traffic]", "[Reliability]", "[Clipboard]", "[Files]", "[Phone]", "[Events]"] {
            XCTAssertTrue(report.contains(section), section)
        }
        XCTAssertTrue(report.contains("Relay: relay.example:7443"))
        XCTAssertTrue(report.contains("Protocol: v2 · notifications, reliable"))
        XCTAssertTrue(report.contains("Outbox: 3"))
        XCTAssertTrue(report.contains("cccccccc…"))
        XCTAssertFalse(report.contains(relayFingerprint))
        XCTAssertFalse(report.contains(macFingerprint))
    }
}
