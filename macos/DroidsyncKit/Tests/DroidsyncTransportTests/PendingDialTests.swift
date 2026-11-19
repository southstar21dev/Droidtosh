import XCTest
@testable import DroidsyncTransport

final class PendingDialTests: XCTestCase {
    private let launch = Date(timeIntervalSince1970: 1_000)

    func testWaitsOnlyWhileStartingUp() {
        XCTAssertTrue(PendingDial.shouldWait(now: launch.addingTimeInterval(2), launchedAt: launch, hasHadPhone: false))
        XCTAssertFalse(PendingDial.shouldWait(now: launch.addingTimeInterval(25), launchedAt: launch, hasHadPhone: false))
        XCTAssertFalse(PendingDial.shouldWait(now: launch.addingTimeInterval(2), launchedAt: launch, hasHadPhone: true))
    }

    func testExpires() {
        let dial = PendingDial(link: "tel:123", deadline: launch.addingTimeInterval(20))
        XCTAssertEqual(dial.usable(now: launch.addingTimeInterval(19)), "tel:123")
        XCTAssertNil(dial.usable(now: launch.addingTimeInterval(21)))
    }
}
