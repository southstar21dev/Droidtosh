import XCTest
import DroidsyncProtocol
@testable import DroidsyncTransport

final class PhoneStatusTrackerTests: XCTestCase {
    private var now = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeTracker() -> PhoneStatusTracker {
        PhoneStatusTracker(clock: { [unowned self] in self.now })
    }

    private func status(_ percent: Int32, charging: Bool = false) -> DSDeviceStatus {
        var status = DSDeviceStatus()
        status.batteryPercent = percent
        status.charging = charging
        return status
    }

    func testStaleAfterTenMinutesOrOnceTheSessionEnds() {
        let tracker = makeTracker()
        XCTAssertFalse(tracker.isStale(), "no status yet")
        tracker.sessionStarted()
        tracker.receive(status(80))
        XCTAssertEqual(tracker.receivedAt, now)
        XCTAssertFalse(tracker.isStale())
        now += 600
        XCTAssertFalse(tracker.isStale())
        now += 1
        XCTAssertTrue(tracker.isStale())
        tracker.receive(status(79))
        XCTAssertFalse(tracker.isStale())
        tracker.sessionEnded()
        XCTAssertTrue(tracker.isStale())
        XCTAssertEqual(tracker.status?.batteryPercent, 79, "the last status stays for \"last seen\"")
    }

    func testStatusFromTheEndedSessionStaysStaleUntilTheNewSessionSendsOne() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        tracker.receive(status(80))
        tracker.sessionEnded()
        XCTAssertTrue(tracker.isStale())
        tracker.sessionStarted()
        XCTAssertTrue(tracker.isStale(), "still the ended session's status")
        tracker.receive(status(60))
        XCTAssertFalse(tracker.isStale())
    }

    func testAlertsAtTwentyThenTenOncePerDischarge() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertNil(tracker.receive(status(25)))
        XCTAssertEqual(tracker.receive(status(20)), 20)
        XCTAssertNil(tracker.receive(status(19)))
        XCTAssertNil(tracker.receive(status(15)))
        XCTAssertEqual(tracker.receive(status(10)), 10)
        XCTAssertNil(tracker.receive(status(9)))
        XCTAssertNil(tracker.receive(status(3)))
    }

    func testChargingResetsAndSuppresses() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertEqual(tracker.receive(status(18)), 20)
        XCTAssertNil(tracker.receive(status(18, charging: true)))
        XCTAssertTrue(tracker.firedThresholds.isEmpty)
        XCTAssertNil(tracker.receive(status(5, charging: true)), "never while charging")
        XCTAssertEqual(tracker.receive(status(19)), 20, "unplugged again: a new discharge")
    }

    func testBigDropAlertsOnceForTheLowestThreshold() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertNil(tracker.receive(status(40)))
        XCTAssertEqual(tracker.receive(status(8)), 10)
        XCTAssertEqual(tracker.firedThresholds, [20, 10])
        XCTAssertNil(tracker.receive(status(7)))
    }

    func testUnknownPercentIsIgnored() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertNil(tracker.receive(status(-1)))
        XCTAssertTrue(tracker.firedThresholds.isEmpty)
        XCTAssertEqual(tracker.receive(status(20)), 20)
        XCTAssertNil(tracker.receive(status(-1)))
        XCTAssertEqual(tracker.firedThresholds, [20], "an unknown reading doesn't re-arm")
    }

    func testFirstStatusOfAFreshAppAlertsWhenAlreadyLow() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertEqual(tracker.receive(status(15)), 20)
    }

    func testDischargeMemorySurvivesReconnects() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertEqual(tracker.receive(status(20)), 20)
        // A flapping connection: each new session repeats the same low reading.
        for _ in 0..<3 {
            tracker.sessionEnded()
            tracker.sessionStarted()
            XCTAssertNil(tracker.receive(status(19)))
        }
        XCTAssertEqual(tracker.receive(status(10)), 10, "a later crossing in the session still alerts")
    }

    func testFirstStatusOfASessionSkipsACatchUpAlert() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertEqual(tracker.receive(status(18)), 20)
        tracker.sessionEnded()
        // The phone crossed 10% while disconnected.
        tracker.sessionStarted()
        XCTAssertNil(tracker.receive(status(8)))
        XCTAssertEqual(tracker.firedThresholds, [20, 10])
        XCTAssertNil(tracker.receive(status(7)))
    }

    func testChargedWhileAwayRearmsWithoutAChargingReport() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        XCTAssertEqual(tracker.receive(status(19)), 20)
        tracker.sessionEnded()
        tracker.sessionStarted()
        XCTAssertNil(tracker.receive(status(90)))
        XCTAssertTrue(tracker.firedThresholds.isEmpty)
        XCTAssertEqual(tracker.receive(status(20)), 20)
        XCTAssertNil(tracker.receive(status(22)), "a one-point bounce doesn't re-arm")
        XCTAssertNil(tracker.receive(status(20)))
    }

    func testResetForgetsEverything() {
        let tracker = makeTracker()
        tracker.sessionStarted()
        tracker.receive(status(10))
        tracker.reset()
        XCTAssertNil(tracker.status)
        XCTAssertNil(tracker.receivedAt)
        XCTAssertTrue(tracker.firedThresholds.isEmpty)
        XCTAssertFalse(tracker.isStale())
    }
}

final class PhoneStatusDisplayTests: XCTestCase {
    func testBattery() {
        var status = DSDeviceStatus()
        status.batteryPercent = -1
        XCTAssertEqual(PhoneStatusDisplay.batteryText(status), "—")
        XCTAssertEqual(PhoneStatusDisplay.batterySymbol(status), "battery.0percent")
        XCTAssertNil(PhoneStatusDisplay.chargingText(status))
        let expected: [(Int32, String)] = [
            (0, "battery.0percent"), (12, "battery.0percent"), (13, "battery.25percent"),
            (37, "battery.25percent"), (50, "battery.50percent"), (80, "battery.75percent"),
            (88, "battery.100percent"), (100, "battery.100percent"),
        ]
        for (percent, symbol) in expected {
            status.batteryPercent = percent
            XCTAssertEqual(PhoneStatusDisplay.batterySymbol(status), symbol, "\(percent)")
        }
        status.batteryPercent = 57
        XCTAssertEqual(PhoneStatusDisplay.batteryText(status), "57%")
        XCTAssertEqual(PhoneStatusDisplay.chargingText(status), "On battery")
        status.charging = true
        status.chargeSource = .wireless
        XCTAssertEqual(PhoneStatusDisplay.batterySymbol(status), "battery.100percent.bolt")
        XCTAssertEqual(PhoneStatusDisplay.chargingText(status), "Charging (Wireless)")
        status.batteryPercent = 5
        XCTAssertFalse(PhoneStatusDisplay.isLowBattery(status))
        status.charging = false
        XCTAssertTrue(PhoneStatusDisplay.isLowBattery(status))
    }

    func testNetworkAndBars() {
        var status = DSDeviceStatus()
        status.wifiLevel = -1
        status.cellularLevel = 3
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "—")
        XCTAssertNil(PhoneStatusDisplay.bars(status))
        status.networkType = .cellular
        status.cellularGeneration = "5G"
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "5G")
        XCTAssertEqual(PhoneStatusDisplay.networkSymbol(status), "cellularbars")
        XCTAssertEqual(PhoneStatusDisplay.bars(status), 3)
        XCTAssertEqual(PhoneStatusDisplay.signalFraction(status), 0.75)
        status.cellularGeneration = ""
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "Cellular")
        status.cellularLevel = -1
        XCTAssertNil(PhoneStatusDisplay.bars(status))
        status.networkType = .wifi
        status.wifiLevel = 9
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "Wi-Fi")
        XCTAssertEqual(PhoneStatusDisplay.bars(status), 4, "clamped")
        status.networkType = .ethernet
        XCTAssertNil(PhoneStatusDisplay.bars(status))
        status.networkType = .none
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "No network")
        status.airplaneMode = true
        XCTAssertEqual(PhoneStatusDisplay.networkLabel(status), "Airplane mode")
        XCTAssertEqual(PhoneStatusDisplay.networkSymbol(status), "airplane")
    }

    func testRinger() {
        var status = DSDeviceStatus()
        XCTAssertEqual(PhoneStatusDisplay.ringerLabel(status), "—")
        status.ringerMode = .normal
        XCTAssertEqual(PhoneStatusDisplay.ringerLabel(status), "Ringer on")
        XCTAssertEqual(PhoneStatusDisplay.ringerSymbol(status), "bell.fill")
        status.ringerMode = .vibrate
        XCTAssertEqual(PhoneStatusDisplay.ringerLabel(status), "Vibrate")
        status.ringerMode = .silent
        XCTAssertEqual(PhoneStatusDisplay.ringerSymbol(status), "bell.slash.fill")
        status.doNotDisturb = true
        XCTAssertEqual(PhoneStatusDisplay.ringerLabel(status), "Do Not Disturb")
        XCTAssertEqual(PhoneStatusDisplay.ringerSymbol(status), "moon.fill")
    }

    func testLastSeen() {
        let seen = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(PhoneStatusDisplay.lastSeenText(seen, now: seen + 30), "last seen just now")
        XCTAssertEqual(PhoneStatusDisplay.lastSeenText(seen, now: seen + 7 * 60 + 5), "last seen 7 min ago")
        XCTAssertEqual(PhoneStatusDisplay.lastSeenText(seen, now: seen + 3 * 3600), "last seen 3 h ago")
        XCTAssertEqual(PhoneStatusDisplay.lastSeenText(seen, now: seen + 86_400), "last seen 1 day ago")
        XCTAssertEqual(PhoneStatusDisplay.lastSeenText(seen, now: seen + 3 * 86_400), "last seen 3 days ago")
    }
}

final class FindPhoneSessionTests: XCTestCase {
    private var now = Date(timeIntervalSince1970: 1_800_000_000)
    private var nextID = 0

    private func makeSession() -> FindPhoneSession {
        FindPhoneSession(clock: { [unowned self] in self.now }, makeID: { [unowned self] in
            self.nextID += 1
            return "req-\(self.nextID)"
        })
    }

    private func state(_ id: String, ringing: Bool, ended: DSFindEndReason = .findEndUnspecified, error: String = "") -> DSFindPhoneState {
        var state = DSFindPhoneState()
        state.requestID = id
        state.ringing = ringing
        state.ended = ended
        state.error = error
        return state
    }

    func testRingThenStopOnPhone() {
        let session = makeSession()
        session.setConnected(true)
        XCTAssertEqual(session.phase, .idle)
        XCTAssertEqual(session.buttonTitle, "Find My Phone")
        XCTAssertNil(session.statusText)
        let request = session.start()
        XCTAssertEqual(request.requestID, "req-1")
        XCTAssertEqual(request.action, .start)
        XCTAssertEqual(session.phase, .requested(waitingForConnection: false))
        XCTAssertEqual(session.statusText, "Ringing…")
        XCTAssertEqual(session.buttonTitle, "Stop Ringing")
        XCTAssertTrue(session.receive(state("req-1", ringing: true)))
        XCTAssertEqual(session.phase, .ringing)
        XCTAssertEqual(session.buttonTitle, "Stop Ringing")
        XCTAssertTrue(session.receive(state("req-1", ringing: false, ended: .findEndStoppedOnPhone)))
        XCTAssertEqual(session.phase, .ended(.stoppedOnPhone))
        XCTAssertEqual(session.statusText, "Stopped on the phone")
        XCTAssertEqual(session.buttonTitle, "Find My Phone")
    }

    func testStopFromMacSendsStop() {
        let session = makeSession()
        session.setConnected(true)
        XCTAssertNil(session.stop(), "nothing to stop")
        _ = session.start()
        session.receive(state("req-1", ringing: true))
        let stop = session.stop()
        XCTAssertEqual(stop?.action, .stop)
        XCTAssertEqual(stop?.requestID, "req-1")
        XCTAssertEqual(session.phase, .ended(.stoppedFromMac))
        XCTAssertEqual(session.statusText, "Stopped")
        session.receive(state("req-1", ringing: false, ended: .findEndStoppedFromMac))
        XCTAssertEqual(session.phase, .ended(.stoppedFromMac))
    }

    func testStopWithdrawsAStartThatWasNeverTransmitted() {
        let session = makeSession()
        let outbox = ReliableStream(streamID: "mac")
        // The phone is away: the START is only queued.
        _ = outbox.stamp(protocolEnvelope(.findPhoneRequest(session.start())), reliable: true, transmitted: false)
        let stop = try! XCTUnwrap(session.stop())
        XCTAssertTrue(outbox.withdrawUnsent { FindPhoneSession.isStart($0, requestID: stop.requestID) })
        XCTAssertEqual(outbox.pendingCount, 0, "neither START nor STOP goes out on the reconnect")
        XCTAssertEqual(session.phase, .ended(.stoppedFromMac))
    }

    func testStopCannotWithdrawATransmittedStart() {
        let session = makeSession()
        session.setConnected(true)
        let outbox = ReliableStream(streamID: "mac")
        let start = session.start()
        _ = outbox.stamp(protocolEnvelope(.findPhoneRequest(start)), reliable: true)
        let stop = try! XCTUnwrap(session.stop())
        XCTAssertFalse(outbox.withdrawUnsent { FindPhoneSession.isStart($0, requestID: stop.requestID) })
        XCTAssertEqual(outbox.pendingCount, 1)
        // Only the START of this request matches.
        var other = start
        other.requestID = "other"
        XCTAssertFalse(FindPhoneSession.isStart(protocolEnvelope(.findPhoneRequest(other)), requestID: start.requestID))
        XCTAssertFalse(FindPhoneSession.isStart(protocolEnvelope(.findPhoneRequest(stop)), requestID: start.requestID))
    }

    func testNoResponseAfterFifteenSecondsWhileConnected() {
        let session = makeSession()
        session.setConnected(true)
        _ = session.start()
        now += 14
        XCTAssertFalse(session.tick())
        now += 1
        XCTAssertTrue(session.tick())
        XCTAssertEqual(session.phase, .noResponse)
        XCTAssertEqual(session.statusText, "Phone didn't respond")
        XCTAssertFalse(session.canStop)
        // A late answer still lands.
        XCTAssertTrue(session.receive(state("req-1", ringing: true)))
        XCTAssertEqual(session.phase, .ringing)
    }

    func testRequestWhileDisconnectedWaitsForReconnect() {
        let session = makeSession()
        _ = session.start()
        XCTAssertEqual(session.phase, .requested(waitingForConnection: true))
        XCTAssertEqual(session.statusText, "Will ring when the phone reconnects")
        now += 60
        XCTAssertFalse(session.tick(), "no response timeout while disconnected")
        session.setConnected(true)
        XCTAssertEqual(session.phase, .requested(waitingForConnection: false))
        now += 14
        XCTAssertFalse(session.tick())
        now += 1
        XCTAssertTrue(session.tick())
        XCTAssertEqual(session.phase, .noResponse)
    }

    func testDisconnectWhileRequestedGoesBackToWaiting() {
        let session = makeSession()
        session.setConnected(true)
        _ = session.start()
        now += 10
        session.setConnected(false)
        XCTAssertEqual(session.phase, .requested(waitingForConnection: true))
        now += 30
        XCTAssertFalse(session.tick())
    }

    func testQueuedRequestExpires() {
        let session = makeSession()
        _ = session.start()
        now += 89
        XCTAssertFalse(session.tick())
        now += 1
        XCTAssertTrue(session.tick())
        XCTAssertEqual(session.phase, .ended(.expired))
    }

    func testRingWithoutEndReportEventuallyEnds() {
        let session = makeSession()
        session.setConnected(true)
        _ = session.start()
        session.receive(state("req-1", ringing: true))
        now += 74
        XCTAssertFalse(session.tick())
        now += 1
        XCTAssertTrue(session.tick())
        XCTAssertEqual(session.phase, .ended(.timedOut))
    }

    func testFailuresMapToUserStrings() {
        let cases: [(String, String)] = [
            ("disabled", "Find my phone is turned off on the phone"),
            ("audio_failed", "The phone couldn't play the sound"),
            ("stale", "The request reached the phone too late — try again"),
            ("", "The phone couldn't ring"),
        ]
        for (error, text) in cases {
            let session = makeSession()
            session.setConnected(true)
            let id = session.start().requestID
            session.receive(state(id, ringing: false, ended: .findEndFailed, error: error))
            XCTAssertEqual(session.phase, .ended(.failed(error)))
            XCTAssertEqual(session.statusText, text)
        }
        XCTAssertEqual(FindPhoneEnd(state("x", ringing: false, ended: .findEndTimeout)), .timedOut)
        XCTAssertEqual(FindPhoneEnd(state("x", ringing: false, ended: .findEndTimeout)).text, "Rang for a minute — stopped")
    }

    func testReportsForOtherRequestsAreIgnoredWhileOneIsOutstanding() {
        let session = makeSession()
        session.setConnected(true)
        _ = session.start()
        session.receive(state("req-1", ringing: false, ended: .findEndStoppedOnPhone))
        _ = session.start()
        XCTAssertFalse(session.receive(state("req-1", ringing: false, ended: .findEndTimeout)))
        XCTAssertEqual(session.phase, .requested(waitingForConnection: false))
        XCTAssertTrue(session.receive(state("req-2", ringing: true)))
        XCTAssertEqual(session.phase, .ringing)
    }

    func testAnUnknownRingIsAdoptedSoItCanBeStopped() {
        let session = makeSession()
        session.setConnected(true)
        XCTAssertFalse(session.receive(state("other", ringing: false, ended: .findEndTimeout)))
        XCTAssertEqual(session.phase, .idle)
        XCTAssertTrue(session.receive(state("other", ringing: true)))
        XCTAssertEqual(session.phase, .ringing)
        XCTAssertEqual(session.stop()?.requestID, "other")
    }

    func testResetGoesIdle() {
        let session = makeSession()
        _ = session.start()
        session.reset()
        XCTAssertEqual(session.phase, .idle)
        XCTAssertNil(session.requestID)
        XCTAssertEqual(session.phaseCode, "idle")
    }
}
