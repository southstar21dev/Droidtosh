import XCTest
@testable import DroidsyncTransport

final class RelayEndpointTests: XCTestCase {
    func testTrimsHostAndParsesPort() {
        XCTAssertEqual(
            RelayEndpoint(host: "  relay.example ", portText: " 7443\n", room: "r"),
            RelayEndpoint(host: "relay.example", port: 7443, room: "r")
        )
    }

    func testRejectsIncompleteSettings() {
        XCTAssertNil(RelayEndpoint(host: "  ", portText: "7443", room: "r"))
        XCTAssertNil(RelayEndpoint(host: "h", portText: "", room: "r"))
        XCTAssertNil(RelayEndpoint(host: "h", portText: "7443", room: ""))
    }

    func testPortRange() {
        XCTAssertNil(RelayEndpoint.port(from: "0"))
        XCTAssertNil(RelayEndpoint.port(from: "65536"))
        XCTAssertNil(RelayEndpoint.port(from: "port"))
        XCTAssertEqual(RelayEndpoint.port(from: "1"), 1)
        XCTAssertEqual(RelayEndpoint.port(from: "65535"), 65535)
    }

    func testGeneratedRoomTokenIsValid() {
        let token = RoomToken.generate()
        XCTAssertTrue(RoomToken.isValid(token))
        XCTAssertNotEqual(token, RoomToken.generate())
    }

    func testRoomTokenValidation() {
        XCTAssertFalse(RoomToken.isValid(""))
        XCTAssertFalse(RoomToken.isValid(String(repeating: "a", count: 42)))
        XCTAssertFalse(RoomToken.isValid(String(repeating: "a", count: 42) + "="))
        XCTAssertTrue(RoomToken.isValid(String(repeating: "a", count: 41) + "-_"))
    }
}
