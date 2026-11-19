import XCTest
@testable import DroidsyncCalls

final class DialAccountPickerTests: XCTestCase {
    func testPrefersTheLastUsedSimWhileThePhoneStillHasIt() {
        XCTAssertEqual(DialAccountPicker.defaultAccount(accounts: ["a", "b"], lastUsed: "b"), "b")
        XCTAssertEqual(DialAccountPicker.defaultAccount(accounts: ["a", "b"], lastUsed: "gone"), "a")
        XCTAssertEqual(DialAccountPicker.defaultAccount(accounts: ["a", "b"], lastUsed: nil), "a")
        XCTAssertNil(DialAccountPicker.defaultAccount(accounts: [], lastUsed: "a"))
    }

    func testTelLinkNormalisesTheUrlText() {
        XCTAssertEqual(DialAccountPicker.telLink(from: "tel:+491701234567"), "tel:+491701234567")
        XCTAssertEqual(DialAccountPicker.telLink(from: "tel://+491701234567"), "tel:+491701234567")
        XCTAssertEqual(DialAccountPicker.telLink(from: "TEL:+49%20170%20123"), "tel:+49170123")
        XCTAssertEqual(DialAccountPicker.telLink(from: "tel:%2B49170"), "tel:+49170")
        XCTAssertNil(DialAccountPicker.telLink(from: "tel:"))
        XCTAssertNil(DialAccountPicker.telLink(from: "https://example.com"))
    }
}
