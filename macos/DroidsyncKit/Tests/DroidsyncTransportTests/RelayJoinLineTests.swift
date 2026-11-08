import XCTest
@testable import DroidsyncTransport

final class RelayJoinLineTests: XCTestCase {
    func testFormatsMacLine() {
        let line = RelayJoinLine(room: "R", role: .mac, fcmToken: nil)
        XCTAssertEqual(line.formatted(), "DROIDSYNC/2 R mac\n")
    }

    func testFormatsPhoneLineWithToken() {
        let line = RelayJoinLine(room: "R", role: .phone, fcmToken: "tok")
        XCTAssertEqual(line.formatted(), "DROIDSYNC/2 R phone tok\n")
    }

    func testParsesUpgradeError() throws {
        XCTAssertEqual(try RelayResponse.parse("ERROR upgrade\n"), .error("upgrade"))
    }

    func testParsesResponses() throws {
        XCTAssertEqual(try RelayResponse.parse("PAIRED\n"), .paired)
        XCTAssertEqual(try RelayResponse.parse("WAIT"), .wait)
        XCTAssertEqual(try RelayResponse.parse("ERROR bad-join\n"), .error("bad-join"))
        XCTAssertThrowsError(try RelayResponse.parse("garbage"))
    }
}
