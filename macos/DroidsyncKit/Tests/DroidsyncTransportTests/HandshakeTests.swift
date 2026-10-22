import XCTest
@testable import DroidsyncProtocol
@testable import DroidsyncTransport

final class HandshakeTests: XCTestCase {
    private let psk = Data(repeating: 0x42, count: 32)
    private func serverNonce() -> Data { Data(repeating: 0x01, count: 32) }
    private func clientNonce() -> Data { Data(repeating: 0x02, count: 32) }

    func testHappyPath() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)

        let challenge = server.start()
        let response = try client.handleChallenge(challenge)
        let ok = try XCTUnwrap(try server.handle(response))
        try client.handleAuthOk(ok)

        XCTAssertEqual(server.state, .authenticated(psk: psk))
        XCTAssertEqual(client.state, .authenticated)
    }

    func testSecondPSKAccepted() throws {
        let other = Data(repeating: 0x99, count: 32)
        var server = ServerHandshake(psks: [other, psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)
        let response = try client.handleChallenge(server.start())
        let ok = try XCTUnwrap(try server.handle(response))
        try client.handleAuthOk(ok)
        XCTAssertEqual(server.state, .authenticated(psk: psk))
    }

    func testWrongPSKRejected() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: Data(repeating: 0x99, count: 32), nonceGenerator: clientNonce)
        let response = try client.handleChallenge(server.start())
        XCTAssertThrowsError(try server.handle(response)) { error in
            XCTAssertEqual(error as? AuthError, .invalidMAC)
        }
        XCTAssertEqual(server.state, .failed)
    }

    func testTamperedAuthOkRejected() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)
        let response = try client.handleChallenge(server.start())
        var ok = try XCTUnwrap(try server.handle(response))
        ok.authOk.mac = Data(repeating: 0x00, count: 32)
        XCTAssertThrowsError(try client.handleAuthOk(ok)) { error in
            XCTAssertEqual(error as? AuthError, .invalidMAC)
        }
        XCTAssertEqual(client.state, .failed)
    }

    func testNegotiatesVersionAndCapabilityIntersection() throws {
        var server = ServerHandshake(
            psks: [psk], capabilities: ["notifications", "calls", "reliable"], nonceGenerator: serverNonce
        )
        var client = ClientHandshake(
            psk: psk, capabilities: ["reliable", "clipboard.text", "notifications"],
            minVersion: 2, maxVersion: 5, nonceGenerator: clientNonce
        )
        let challenge = server.start()
        XCTAssertEqual(challenge.authChallenge.minVersion, 2)
        XCTAssertEqual(challenge.authChallenge.maxVersion, 2)
        let ok = try XCTUnwrap(try server.handle(try client.handleChallenge(challenge)))
        XCTAssertEqual(ok.authOk.version, 2)
        XCTAssertEqual(ok.authOk.capabilities, ["notifications", "reliable"])
        try client.handleAuthOk(ok)
        XCTAssertEqual(server.negotiation, Negotiation(version: 2, capabilities: ["notifications", "reliable"]))
        XCTAssertEqual(client.negotiation, server.negotiation)
        XCTAssertTrue(try XCTUnwrap(client.negotiation).supports("reliable"))
        XCTAssertFalse(try XCTUnwrap(client.negotiation).supports("calls"))
    }

    func testRejectsPeerWithoutOverlappingVersion() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, minVersion: 3, maxVersion: 4, nonceGenerator: clientNonce)
        let response = try client.handleChallenge(server.start())
        XCTAssertThrowsError(try server.handle(response)) { error in
            XCTAssertEqual(error as? AuthError, .unsupportedVersion)
        }
        XCTAssertEqual(server.state, .failed)
    }

    func testRejectsV1PeerThatSendsNoVersionRange() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)
        var response = try client.handleChallenge(server.start())
        response.authResponse.minVersion = 0
        response.authResponse.maxVersion = 0
        XCTAssertThrowsError(try server.handle(response)) { error in
            XCTAssertEqual(error as? AuthError, .unsupportedVersion)
        }
    }

    func testClientRejectsAuthOkOutsideItsRange() throws {
        var server = ServerHandshake(psks: [psk], nonceGenerator: serverNonce)
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)
        var ok = try XCTUnwrap(try server.handle(try client.handleChallenge(server.start())))
        ok.authOk.version = 9
        XCTAssertThrowsError(try client.handleAuthOk(ok)) { error in
            XCTAssertEqual(error as? AuthError, .unsupportedVersion)
        }
    }

    func testVersionSelection() {
        XCTAssertEqual(Negotiation.version(localMin: 2, localMax: 3, peerMin: 2, peerMax: 2), 2)
        XCTAssertEqual(Negotiation.version(localMin: 2, localMax: 3, peerMin: 3, peerMax: 9), 3)
        XCTAssertNil(Negotiation.version(localMin: 2, localMax: 2, peerMin: 3, peerMax: 3))
        XCTAssertNil(Negotiation.version(localMin: 2, localMax: 2, peerMin: 0, peerMax: 0))
    }

    func testOutOfOrderRejected() {
        var client = ClientHandshake(psk: psk, nonceGenerator: clientNonce)
        XCTAssertThrowsError(try client.handleAuthOk(DSEnvelope())) { error in
            XCTAssertEqual(error as? AuthError, .invalidMessage)
        }
    }
}
