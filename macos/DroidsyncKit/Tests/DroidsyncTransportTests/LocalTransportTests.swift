import XCTest
import NIOCore
import DroidsyncProtocol
@testable import DroidsyncTransport

final class LocalTransportTests: XCTestCase {
    private static let room = String(repeating: "a", count: 43)
    private let psk = Data(repeating: 0x11, count: 32)

    private func makeTransport(
        relay: InProcessRelay? = nil,
        identity: TLSIdentity,
        onStateChange: @escaping (RelayTransport.State) -> Void = { _ in },
        onAuthenticated: @escaping (AuthenticatedSession) -> Void = { _ in },
        onDisconnected: @escaping () -> Void = {}
    ) -> RelayTransport {
        RelayTransport(
            relay: relay.map { RelayEndpoint(host: "127.0.0.1", port: UInt16($0.port), room: Self.room) },
            local: LocalListenerOptions(port: 0, advertisement: nil),
            identity: identity,
            pskProvider: { [psk] in [psk] },
            relayFingerprint: relay?.fingerprint,
            onStateChange: onStateChange,
            onClientAuthenticated: onAuthenticated,
            onClientDisconnected: onDisconnected,
            onEnvelope: { _ in }
        )
    }

    /// Runs the phone side of auth and Resume, then pings; `onPong` fires on the Mac's answer.
    private func drive(_ phone: RelayPhoneClient, onPong: @escaping () -> Void) {
        var handshake = ClientHandshake(psk: psk)
        phone.onEnvelope = { envelope in
            switch handshake.state {
            case .initial:
                if let response = try? handshake.handleChallenge(envelope) { phone.send(response) }
            case .awaitingAuthOk:
                try? handshake.handleAuthOk(envelope)
                phone.send(RelayTransportTests.phoneResume())
                var ping = DSPing()
                ping.sentAtMs = 42
                phone.send(makeEnvelope(.ping(ping)))
            case .authenticated:
                if case .pong = envelope.payload { onPong() }
            case .failed:
                XCTFail("phone handshake failed")
            }
        }
    }

    private func listeningPort(_ transport: RelayTransport) -> Int? {
        var port: Int?
        _ = waitUntil {
            if case .listening(let bound) = transport.localState { port = bound }
            return port != nil
        }
        return port
    }

    func testLocalOnlySessionAuthenticatesDirectly() throws {
        let identity = try TLSIdentityGenerator.generate()
        let authenticated = expectation(description: "authenticated")
        let disconnected = expectation(description: "disconnected")
        var sessions: [AuthenticatedSession] = []
        var states: [RelayTransport.State] = []
        let transport = makeTransport(
            identity: identity,
            onStateChange: { states.append($0) },
            onAuthenticated: { sessions.append($0); authenticated.fulfill() },
            onDisconnected: { disconnected.fulfill() }
        )
        defer { transport.stop() }
        transport.start()
        let port = try XCTUnwrap(listeningPort(transport))
        XCTAssertTrue(waitUntil { transport.state == .waitingForPhone }, "state=\(transport.state)")

        let phone = try RelayPhoneClient(room: nil, identity: identity)
        defer { phone.shutdown() }
        let pong = expectation(description: "pong")
        drive(phone) { pong.fulfill() }
        try phone.start(host: "127.0.0.1", port: port)
        wait(for: [authenticated, pong], timeout: 10)

        XCTAssertEqual(sessions.map(\.path), [.local])
        XCTAssertEqual(transport.state, .connected)
        XCTAssertEqual(transport.currentSessionPath, .local)
        XCTAssertFalse(states.contains(.connecting), "a local-only transport never dials a relay")

        phone.cancel()
        wait(for: [disconnected], timeout: 5)
        XCTAssertTrue(waitUntil { transport.state == .waitingForPhone }, "state=\(transport.state)")
        XCTAssertNil(transport.currentSessionPath)
    }

    func testLocalSessionReplacesRelaySession() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let identity = try TLSIdentityGenerator.generate()
        var sessions: [AuthenticatedSession] = []
        var disconnects = 0
        let transport = makeTransport(
            relay: relay,
            identity: identity,
            onAuthenticated: { sessions.append($0) },
            onDisconnected: { disconnects += 1 }
        )
        defer { transport.stop() }
        transport.start()
        let port = try XCTUnwrap(listeningPort(transport))
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 })

        let relayPhone = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { relayPhone.shutdown() }
        let relayClosed = expectation(description: "relay session closed")
        relayClosed.assertForOverFulfill = false
        relayPhone.onClosed = { relayClosed.fulfill() }
        let relayPong = expectation(description: "relay pong")
        drive(relayPhone) { relayPong.fulfill() }
        try relayPhone.start(host: "127.0.0.1", port: relay.port)
        wait(for: [relayPong], timeout: 10)
        XCTAssertEqual(transport.currentSessionPath, .relay)

        let localPhone = try RelayPhoneClient(room: nil, identity: identity)
        defer { localPhone.shutdown() }
        let localPong = expectation(description: "local pong")
        drive(localPhone) { localPong.fulfill() }
        try localPhone.start(host: "127.0.0.1", port: port)
        wait(for: [localPong, relayClosed], timeout: 10)

        XCTAssertTrue(waitUntil { sessions.count == 2 && disconnects == 1 }, "sessions=\(sessions.count) disconnects=\(disconnects)")
        XCTAssertEqual(sessions.map(\.path), [.relay, .local])
        XCTAssertEqual(transport.currentSessionPath, .local)
        XCTAssertEqual(transport.state, .connected)
        // The relay connection is re-parked for the next time the phone needs it.
        XCTAssertTrue(waitUntil(timeout: 10) { relay.parkedMacCount == 1 })
    }

    func testSuspendClosesListenerAndResumeReopensIt() throws {
        let identity = try TLSIdentityGenerator.generate()
        let transport = makeTransport(identity: identity)
        defer { transport.stop() }
        transport.start()
        XCTAssertNotNil(listeningPort(transport))

        transport.suspend()
        XCTAssertTrue(waitUntil { transport.localState == .off && transport.state == .stopped }, "local=\(transport.localState)")

        transport.resume()
        XCTAssertNotNil(listeningPort(transport))
        XCTAssertTrue(waitUntil { transport.state == .waitingForPhone })

        transport.stop()
        XCTAssertEqual(transport.localState, .off)
    }

    func testUnauthenticatedConnectionIsDroppedAfterTheDeadline() throws {
        let identity = try TLSIdentityGenerator.generate()
        let transport = makeTransport(identity: identity)
        defer { transport.stop() }
        transport.start()
        let port = try XCTUnwrap(listeningPort(transport))

        // A client that completes TLS but never answers the challenge.
        let idle = try RelayPhoneClient(room: nil, identity: identity)
        defer { idle.shutdown() }
        let closed = expectation(description: "closed by the Mac")
        closed.assertForOverFulfill = false
        idle.onClosed = { closed.fulfill() }
        try idle.start(host: "127.0.0.1", port: port)
        wait(for: [closed], timeout: LocalListenerOptions.authTimeout + 5)
    }

    func testAddressPolicy() {
        let allowed: [[UInt8]] = [[127, 0, 0, 1], [10, 1, 2, 3], [172, 16, 0, 1], [172, 31, 255, 1], [192, 168, 1, 20], [169, 254, 3, 4], [100, 100, 1, 1]]
        let refused: [[UInt8]] = [[8, 8, 8, 8], [172, 32, 0, 1], [192, 169, 0, 1], [100, 128, 0, 1], [1, 1, 1, 1]]
        allowed.forEach { XCTAssertTrue(LocalAddressPolicy.isAllowed(ipv4: $0), "\($0)") }
        refused.forEach { XCTAssertFalse(LocalAddressPolicy.isAllowed(ipv4: $0), "\($0)") }

        var loopback = [UInt8](repeating: 0, count: 16); loopback[15] = 1
        var mappedPrivate = [UInt8](repeating: 0, count: 16); mappedPrivate[10] = 0xFF; mappedPrivate[11] = 0xFF
        mappedPrivate[12...15] = [192, 168, 0, 9]
        var mappedPublic = mappedPrivate; mappedPublic[12...15] = [8, 8, 4, 4]
        var ula = [UInt8](repeating: 0, count: 16); ula[0] = 0xFD
        var linkLocal = [UInt8](repeating: 0, count: 16); linkLocal[0] = 0xFE; linkLocal[1] = 0x80
        var global = [UInt8](repeating: 0, count: 16); global[0] = 0x20; global[1] = 0x01
        XCTAssertTrue(LocalAddressPolicy.isAllowed(ipv6: loopback))
        XCTAssertTrue(LocalAddressPolicy.isAllowed(ipv6: mappedPrivate))
        XCTAssertFalse(LocalAddressPolicy.isAllowed(ipv6: mappedPublic))
        XCTAssertTrue(LocalAddressPolicy.isAllowed(ipv6: ula))
        XCTAssertTrue(LocalAddressPolicy.isAllowed(ipv6: linkLocal))
        XCTAssertFalse(LocalAddressPolicy.isAllowed(ipv6: global))

        XCTAssertTrue(LocalAddressPolicy.isAllowed(try? SocketAddress(ipAddress: "192.168.4.2", port: 1)))
        XCTAssertFalse(LocalAddressPolicy.isAllowed(try? SocketAddress(ipAddress: "203.0.113.9", port: 1)))
        XCTAssertTrue(LocalAddressPolicy.isAllowed(try? SocketAddress(ipAddress: "::1", port: 1)))
        XCTAssertFalse(LocalAddressPolicy.isAllowed(nil))
    }

    func testAdvertisementPublishesOnlyAFingerprintPrefix() {
        let fingerprint = String(repeating: "ab", count: 32)
        let txt = LocalAdvertisement(name: "Mac", fingerprint: fingerprint).txtRecord
        XCTAssertEqual(txt["fp"], Data(String(fingerprint.prefix(16)).utf8))
        XCTAssertEqual(txt["v"], Data("2".utf8))
    }

    private func waitUntil(timeout: TimeInterval = 5, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }
        return condition()
    }
}
