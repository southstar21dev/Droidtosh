import XCTest
@testable import DroidsyncTransport

final class ConnectionCauseTests: XCTestCase {
    private func cause(
        _ state: RelayTransport.State,
        hasRelay: Bool = true,
        localListening: Bool = false,
        pairingExpired: Bool = false
    ) -> ConnectionCause? {
        ConnectionCause.derive(state: state, hasRelay: hasRelay, localListening: localListening, pairingExpired: pairingExpired)
    }

    func testNothingToExplainWhileConnectedStoppedOrConnecting() {
        XCTAssertNil(cause(.connected))
        XCTAssertNil(cause(.stopped))
        XCTAssertNil(cause(.connecting))
    }

    func testWaitingForThePhoneThroughARelay() {
        let result = cause(.waitingForPhone)
        XCTAssertEqual(result?.kind, .waitingForPhone)
        XCTAssertEqual(result?.headline, "Mac is waiting for the phone")
    }

    func testLocalOnlyModeSaysThePhoneIsNotOnThisNetwork() {
        XCTAssertEqual(cause(.waitingForPhone, hasRelay: false, localListening: true)?.kind, .phoneNotOnNetwork)
        XCTAssertEqual(cause(.connecting, hasRelay: false, localListening: true)?.headline, "Phone not on this network (local mode)")
    }

    func testRelayFailuresMentionTheLocalFallbackWhenItIsUp() {
        XCTAssertEqual(cause(.failed("x"))?.kind, .relayUnreachable)
        XCTAssertEqual(cause(.failed("x"))?.headline, "Relay unreachable")
        XCTAssertTrue(cause(.failed("x"), localListening: true)?.detail.contains("local network") == true)
        XCTAssertEqual(cause(.failed("x"), hasRelay: false)?.kind, .localNetworkUnavailable)
    }

    func testCertificateAndVersionProblems() {
        XCTAssertEqual(cause(.relayCertificateMismatch("abc"))?.headline, "Relay certificate changed")
        XCTAssertEqual(cause(.relayRequiresUpgrade)?.kind, .relayNeedsUpdate)
    }

    func testAnExpiredPairingWinsWhileWaiting() {
        XCTAssertEqual(cause(.waitingForPhone, pairingExpired: true)?.headline, "Pairing expired")
        XCTAssertEqual(cause(.waitingForPhone, hasRelay: false, pairingExpired: true)?.kind, .pairingExpired)
        // A relay failure is still the more urgent thing to say.
        XCTAssertEqual(cause(.failed("x"), pairingExpired: true)?.kind, .relayUnreachable)
    }

    func testPairingExpiredNeedsARecentFailureWithNoSessionSince() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let failure = DiagnosticsEvent(date: now.addingTimeInterval(-60), kind: .authFailure, message: "phone failed the pairing-key check")
        let session = DiagnosticsEvent(date: now.addingTimeInterval(-30), kind: .state, message: "Phone session started")
        let old = DiagnosticsEvent(date: now.addingTimeInterval(-3_600), kind: .authFailure, message: "phone failed the pairing-key check")
        XCTAssertFalse(ConnectionCause.pairingExpiredRecently(events: [], now: now))
        XCTAssertTrue(ConnectionCause.pairingExpiredRecently(events: [failure], now: now))
        XCTAssertFalse(ConnectionCause.pairingExpiredRecently(events: [failure, session], now: now))
        XCTAssertFalse(ConnectionCause.pairingExpiredRecently(events: [old], now: now))
    }
}
