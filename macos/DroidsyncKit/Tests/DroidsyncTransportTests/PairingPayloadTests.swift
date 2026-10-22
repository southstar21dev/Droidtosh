import XCTest
@testable import DroidsyncTransport

final class PairingPayloadTests: XCTestCase {
    static let room = String(repeating: "A", count: 43)
    static let relayFingerprint = String(repeating: "c4", count: 32)

    private func payload() -> PairingPayload {
        PairingPayload(
            deviceID: "mac-1",
            deviceName: "Work Mac",
            host: "192.168.1.20",
            port: 8765,
            room: Self.room,
            fingerprint: String(repeating: "a", count: 64),
            relayFingerprint: Self.relayFingerprint,
            psk: Data(repeating: 0x5A, count: 32)
        )
    }

    func testRoundTrip() throws {
        let decoded = try PairingPayload.parse(payload().uri())
        XCTAssertEqual(decoded, payload())
        XCTAssertTrue(payload().uri().contains("v=2"))
        XCTAssertTrue(payload().uri().contains("rfp=\(Self.relayFingerprint)"))
    }

    func testRejectsForeignScheme() {
        XCTAssertThrowsError(try PairingPayload.parse("https://example.com")) { error in
            XCTAssertEqual(error as? PairingError, .malformed)
        }
    }

    func testRejectsUnsupportedVersion() {
        let uri = payload().uri().replacingOccurrences(of: "v=2", with: "v=1")
        XCTAssertThrowsError(try PairingPayload.parse(uri)) { error in
            XCTAssertEqual(error as? PairingError, .unsupportedVersion(1))
        }
    }

    func testRequiresValidRelayFingerprint() {
        for bad in ["", "abc", String(repeating: "C4", count: 32), String(repeating: "zz", count: 32)] {
            let uri = payload().uri()
                .replacingOccurrences(of: "rfp=\(Self.relayFingerprint)", with: "rfp=\(bad)")
            XCTAssertThrowsError(try PairingPayload.parse(uri), "rfp \(bad) should be rejected")
        }
        let missing = payload().uri().replacingOccurrences(of: "&rfp=\(Self.relayFingerprint)", with: "")
        XCTAssertThrowsError(try PairingPayload.parse(missing)) { error in
            XCTAssertEqual(error as? PairingError, .malformed)
        }
    }

    func testRelayFingerprintNormalization() {
        let colons = Array(repeating: "C4", count: 32).joined(separator: ":")
        XCTAssertEqual(RelayFingerprint.normalized(colons), Self.relayFingerprint)
        XCTAssertEqual(RelayFingerprint.normalized(" \(Self.relayFingerprint)\n"), Self.relayFingerprint)
        XCTAssertNil(RelayFingerprint.normalized("not a fingerprint"))
    }

    func testRejectsWrongPSKLength() {
        let short = PairingPayload(
            deviceID: "mac-1", deviceName: "Mac", host: "10.0.0.2", port: 1234,
            room: Self.room, fingerprint: "fp", relayFingerprint: Self.relayFingerprint,
            psk: Data(repeating: 0x00, count: 16)
        )
        XCTAssertThrowsError(try PairingPayload.parse(short.uri())) { error in
            XCTAssertEqual(error as? PairingError, .invalidPSKLength)
        }
    }

    func testRoomRoundTrip() throws {
        let payload = PairingPayload(
            deviceID: UUID().uuidString,
            deviceName: "Mac",
            host: "relay.example.com",
            port: 7443,
            room: String(repeating: "A", count: 43),
            fingerprint: String(repeating: "ab", count: 32),
            relayFingerprint: Self.relayFingerprint,
            psk: Data(repeating: 7, count: 32)
        )
        let parsed = try PairingPayload.parse(payload.uri())
        XCTAssertEqual(parsed.room, payload.room)
    }

    func testRejectsMissingRoom() {
        let uri = "droidsync://pair?v=2&did=x&name=Mac&host=h&port=1&fp=\(String(repeating: "ab", count: 32))&rfp=\(Self.relayFingerprint)&psk=\(Base64URL.encode(Data(repeating: 7, count: 32)))"
        XCTAssertThrowsError(try PairingPayload.parse(uri)) { error in
            XCTAssertEqual(error as? PairingError, .malformed)
        }
    }

    func testRejectsInvalidRoom() {
        let invalidRooms = [
            String(repeating: "A", count: 42),
            String(repeating: "A", count: 44),
            String(repeating: "A", count: 42) + "!",
            String(repeating: "A", count: 42) + "=",
            "",
        ]
        for room in invalidRooms {
            let payload = PairingPayload(
                deviceID: "mac-1", deviceName: "Mac", host: "10.0.0.2", port: 1234,
                room: room, fingerprint: "fp", relayFingerprint: Self.relayFingerprint,
                psk: Data(repeating: 0x5A, count: 32)
            )
            XCTAssertThrowsError(try PairingPayload.parse(payload.uri()), "room \(room) should be rejected") { error in
                XCTAssertEqual(error as? PairingError, .malformed)
            }
        }
    }

    func testRelayModeIsTheDefault() throws {
        let uri = payload().uri().replacingOccurrences(of: "&mode=relay", with: "")
        XCTAssertFalse(uri.contains("mode="))
        XCTAssertEqual(try PairingPayload.parse(uri).mode, .relay)
        XCTAssertFalse(payload().uri().contains("lport="))
    }

    func testLocalModeOmitsTheRelay() throws {
        let local = PairingPayload(
            deviceID: "mac-1", deviceName: "Work Mac", host: "relay.example", port: 7443,
            room: Self.room, fingerprint: String(repeating: "a", count: 64),
            relayFingerprint: Self.relayFingerprint, psk: Data(repeating: 0x5A, count: 32),
            mode: .local, localHost: "192.168.1.20", localPort: 7444
        )
        let uri = local.uri()
        XCTAssertTrue(uri.contains("mode=local"))
        XCTAssertTrue(uri.contains("lhost=192.168.1.20"))
        XCTAssertTrue(uri.contains("lport=7444"))
        XCTAssertFalse(uri.contains("rfp="))
        XCTAssertFalse(uri.contains("host=relay"))
        let decoded = try PairingPayload.parse(uri)
        XCTAssertEqual(decoded.mode, .local)
        XCTAssertNil(decoded.host)
        XCTAssertNil(decoded.relayFingerprint)
        XCTAssertEqual(decoded.localHost, "192.168.1.20")
        XCTAssertEqual(decoded.localPort, 7444)

        let noPort = uri.replacingOccurrences(of: "&lport=7444", with: "")
        XCTAssertThrowsError(try PairingPayload.parse(noPort))
    }

    func testBothModeCarriesRelayAndLocal() throws {
        let both = PairingPayload(
            deviceID: "mac-1", deviceName: "Work Mac", host: "relay.example", port: 7443,
            room: Self.room, fingerprint: String(repeating: "a", count: 64),
            relayFingerprint: Self.relayFingerprint, psk: Data(repeating: 0x5A, count: 32),
            mode: .both, localHost: nil, localPort: 7444
        )
        let decoded = try PairingPayload.parse(both.uri())
        XCTAssertEqual(decoded, both)
        let noRelayPin = both.uri().replacingOccurrences(of: "&rfp=\(Self.relayFingerprint)", with: "")
        XCTAssertThrowsError(try PairingPayload.parse(noRelayPin))
        XCTAssertThrowsError(try PairingPayload.parse(both.uri().replacingOccurrences(of: "mode=both", with: "mode=lan")))
    }

    func testBase64URLRoundTrip() {
        let data = Data((0..<64).map { UInt8($0) })
        XCTAssertEqual(Base64URL.decode(Base64URL.encode(data)), data)
    }
}
