import XCTest
@testable import DroidsyncNotifications

final class NotificationPauseTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private func date(_ iso: String) -> Date {
        ISO8601DateFormatter().date(from: iso)!
    }

    func testFixedDurations() {
        let now = date("2026-11-19T10:00:00Z")
        XCTAssertEqual(NotificationPause.thirtyMinutes.endDate(from: now, calendar: calendar), date("2026-11-19T10:30:00Z"))
        XCTAssertEqual(NotificationPause.oneHour.endDate(from: now, calendar: calendar), date("2026-11-19T11:00:00Z"))
        XCTAssertEqual(NotificationPause.twoHours.endDate(from: now, calendar: calendar), date("2026-11-19T12:00:00Z"))
    }

    func testTomorrowMorningIsEightTheNextDay() {
        XCTAssertEqual(
            NotificationPause.tomorrowMorning.endDate(from: date("2026-11-19T23:30:00Z"), calendar: calendar),
            date("2026-11-20T08:00:00Z")
        )
        XCTAssertEqual(
            NotificationPause.tomorrowMorning.endDate(from: date("2026-11-19T01:00:00Z"), calendar: calendar),
            date("2026-11-20T08:00:00Z")
        )
    }

    func testUntilResumedNeverEnds() {
        let end = NotificationPause.untilResumed.endDate(from: Date(), calendar: calendar)
        XCTAssertTrue(NotificationPause.isIndefinite(end))
        XCTAssertEqual(NotificationPause.statusText(until: end, now: Date(), calendar: calendar), "Paused")
    }

    func testStatusTextNamesTheDayWhenNotToday() {
        let now = date("2026-11-19T10:00:00Z")
        let text = NotificationPause.statusText(until: date("2026-11-20T08:00:00Z"), now: now, calendar: calendar)
        XCTAssertTrue(text.hasPrefix("Paused until tomorrow"))
        let same = NotificationPause.statusText(until: date("2026-11-19T10:30:00Z"), now: now, calendar: calendar)
        XCTAssertTrue(same.hasPrefix("Paused until "))
        XCTAssertFalse(same.contains("tomorrow"))
    }
}
