import XCTest
import DroidsyncProtocol
@testable import DroidsyncTransport

final class PhoneStatusBackgroundTests: XCTestCase {
    func testBackgroundRestrictedFollowsTheReportedFlag() {
        var status = DSDeviceStatus()
        XCTAssertFalse(PhoneStatusDisplay.isBackgroundRestricted(status), "an older phone never sets it")
        status.backgroundRestricted = true
        status.backgroundReason = .batteryOptimized
        XCTAssertTrue(PhoneStatusDisplay.isBackgroundRestricted(status))
    }

    func testBackgroundFieldsSurviveAWireRoundTrip() throws {
        var status = DSDeviceStatus()
        status.backgroundRestricted = true
        status.backgroundReason = .vendorBattery
        let decoded = try DSDeviceStatus(serializedBytes: try status.serializedData())
        XCTAssertTrue(decoded.backgroundRestricted)
        XCTAssertEqual(decoded.backgroundReason, .vendorBattery)
    }

    func testAnOlderPhonesStatusDecodesAsNotRestricted() throws {
        var status = DSDeviceStatus()
        status.batteryPercent = 50
        let decoded = try DSDeviceStatus(serializedBytes: try status.serializedData())
        XCTAssertFalse(decoded.backgroundRestricted)
        XCTAssertEqual(decoded.backgroundReason, .unspecified)
    }
}
