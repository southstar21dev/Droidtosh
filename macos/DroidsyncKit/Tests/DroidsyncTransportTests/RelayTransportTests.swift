import XCTest
import NIOCore
import NIOEmbedded
import NIOTLS
import DroidsyncProtocol
@testable import DroidsyncTransport

final class RelayTransportTests: XCTestCase {
    private static let room = String(repeating: "a", count: 43)

    private func makeTransport(relay: InProcessRelay) throws -> RelayTransport {
        let psk = Data(repeating: 0x11, count: 32)
        return RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: try TLSIdentityGenerator.generate(),
            pskProvider: { [psk] },
            relayFingerprint: relay.fingerprint,
            onStateChange: { _ in },
            onClientAuthenticated: { _ in },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
    }

    func testConnectsAndParks() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)

        XCTAssertTrue(
            waitUntil { relay.connectAttempts == 1 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )
    }

    func testReconnectsWhenRelayClosesParkedConnection() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)
        XCTAssertTrue(
            waitUntil { relay.connectAttempts == 1 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )

        let reParked = expectation(description: "reconnected and parked")
        transport.onStateChange = { state in
            if state == .waitingForPhone { reParked.fulfill() }
        }
        relay.closeParkedMac()
        wait(for: [reParked], timeout: 5)

        XCTAssertTrue(
            waitUntil { relay.connectAttempts == 2 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )
    }

    func testSuspendStopsReconnectsAndResumeRestarts() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)
        XCTAssertTrue(
            waitUntil { relay.connectAttempts == 1 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )

        transport.suspend()
        let attemptsAfterSuspend = relay.connectAttempts
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertEqual(relay.connectAttempts, attemptsAfterSuspend)

        let reParked = expectation(description: "re-parked")
        transport.onStateChange = { state in
            if state == .waitingForPhone { reParked.fulfill() }
        }
        transport.resume()
        wait(for: [reParked], timeout: 5)
        XCTAssertTrue(
            waitUntil { relay.connectAttempts == attemptsAfterSuspend + 1 },
            "attempts=\(relay.connectAttempts) expected=\(attemptsAfterSuspend + 1)"
        )
    }

    func testReconnectIfIdleReparksImmediately() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)
        XCTAssertTrue(waitUntil { relay.connectAttempts == 1 && relay.parkedMacCount == 1 })

        transport.onStateChange = { _ in }
        transport.reconnectIfIdle()
        XCTAssertTrue(
            waitUntil(timeout: 2) { relay.connectAttempts == 2 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )
        XCTAssertEqual(transport.state, .waitingForPhone)
    }

    func testOversizedRelayResponseFailsTheAttempt() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        relay.setMacJoinResponse(Data(repeating: UInt8(ascii: "x"), count: 600))
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let failed = expectation(description: "attempt failed")
        transport.onStateChange = { state in
            if case .failed(let message) = state, message.contains("exceeds") { failed.fulfill() }
        }
        transport.start()
        wait(for: [failed], timeout: 5)
    }

    func testRelayForgetsParkedMacWhenClosed() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 })

        transport.onStateChange = { _ in }
        relay.closeParkedMac()
        XCTAssertTrue(
            waitUntil(timeout: 2) { relay.parkedMacCount == 0 },
            "parked=\(relay.parkedMacCount)"
        )
    }

    func testRetainsBytesArrivingWithPairedResponse() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }

        let parked = expectation(description: "waiting for phone")
        transport.onStateChange = { state in
            if state == .waitingForPhone { parked.fulfill() }
        }
        transport.start()
        wait(for: [parked], timeout: 5)
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 })

        let trailing = Data([0x16, 0x03, 0x01, 0x00, 0x2A])
        relay.setPairedTrailingBytes(trailing)
        let phone = try relay.connectPhone()
        var joinLine = phone.allocator.buffer(capacity: 64)
        joinLine.writeString("DROIDSYNC/2 \(Self.room) phone\n")
        try phone.writeAndFlush(joinLine).wait()

        XCTAssertTrue(
            waitUntil { transport.postPairedLeftoverForTesting == trailing },
            "leftover=\(String(describing: transport.postPairedLeftoverForTesting))"
        )
        XCTAssertEqual(relay.pairedCount, 1)
    }

    func testRelayHandshakeHelloPingPongAndRepark() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }

        let identity = try TLSIdentityGenerator.generate()
        let psk = Data(repeating: 0x11, count: 32)

        let gotHello = expectation(description: "server got hello")
        let gotPong = expectation(description: "client got pong")
        let disconnected = expectation(description: "client disconnected")
        disconnected.assertForOverFulfill = false
        let firstAuthenticated = expectation(description: "first authenticated")
        let secondAuthenticated = expectation(description: "second authenticated")
        let becameConnected = expectation(description: "state became connected")
        becameConnected.assertForOverFulfill = false

        var authenticationCount = 0
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: identity,
            pskProvider: { [psk] },
            relayFingerprint: relay.fingerprint,
            onStateChange: { state in
                if state == .connected { becameConnected.fulfill() }
            },
            onClientAuthenticated: { _ in
                authenticationCount += 1
                if authenticationCount == 1 {
                    firstAuthenticated.fulfill()
                } else if authenticationCount == 2 {
                    secondAuthenticated.fulfill()
                }
            },
            onClientDisconnected: { disconnected.fulfill() },
            onEnvelope: { envelope in
                if case .hello = envelope.payload { gotHello.fulfill() }
            }
        )
        defer { transport.stop() }

        transport.start()
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 }, "parked=\(relay.parkedMacCount)")

        let phone = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { phone.shutdown() }
        var handshake = ClientHandshake(psk: psk)
        var authenticatedEnvelopeHandler: ((DSEnvelope) -> Void)?
        phone.onEnvelope = { envelope in
            switch handshake.state {
            case .initial:
                if let response = try? handshake.handleChallenge(envelope) { phone.send(response) }
            case .awaitingAuthOk:
                try? handshake.handleAuthOk(envelope)
                phone.send(Self.phoneResume())
                var hello = DSHello()
                hello.deviceName = "Test Phone"
                hello.androidVersion = "15"
                hello.capabilities = ["notifications", "calls"]
                phone.send(makeEnvelope(.hello(hello)))
                var ping = DSPing()
                ping.sentAtMs = 42
                phone.send(makeEnvelope(.ping(ping)))
            case .authenticated:
                authenticatedEnvelopeHandler?(envelope)
                if case .pong = envelope.payload { gotPong.fulfill() }
            case .failed:
                XCTFail("client handshake failed")
            }
        }

        try phone.start(host: "127.0.0.1", port: relay.port)
        wait(for: [firstAuthenticated, becameConnected, gotHello, gotPong], timeout: 10)

        let gotMuteList = expectation(description: "client got mute list")
        authenticatedEnvelopeHandler = { envelope in
            if case .muteListSet = envelope.payload { gotMuteList.fulfill() }
        }
        var muteList = DSMuteListSet()
        muteList.packages = ["com.example.chatty"]
        transport.send(makeEnvelope(.muteListSet(muteList)))
        wait(for: [gotMuteList], timeout: 5)

        phone.cancel()
        wait(for: [disconnected], timeout: 5)
        XCTAssertTrue(
            waitUntil(timeout: 10) { relay.connectAttempts >= 2 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )

        let phone2 = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { phone2.shutdown() }
        var handshake2 = ClientHandshake(psk: psk)
        let secondPong = expectation(description: "second pong")
        phone2.onEnvelope = { envelope in
            switch handshake2.state {
            case .initial:
                if let response = try? handshake2.handleChallenge(envelope) { phone2.send(response) }
            case .awaitingAuthOk:
                try? handshake2.handleAuthOk(envelope)
                phone2.send(Self.phoneResume())
                var ping = DSPing()
                ping.sentAtMs = 7
                phone2.send(makeEnvelope(.ping(ping)))
            case .authenticated:
                if case .pong = envelope.payload { secondPong.fulfill() }
            case .failed:
                XCTFail("second client handshake failed")
            }
        }

        try phone2.start(host: "127.0.0.1", port: relay.port)
        wait(for: [secondAuthenticated, secondPong], timeout: 10)
    }

    func testRelayRejectsUnsupportedProtocolVersion() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }

        let identity = try TLSIdentityGenerator.generate()
        let psk = Data(repeating: 0x11, count: 32)
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: identity,
            pskProvider: { [psk] },
            relayFingerprint: relay.fingerprint,
            onStateChange: { _ in },
            onClientAuthenticated: { _ in },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
        defer { transport.stop() }

        transport.start()
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 }, "parked=\(relay.parkedMacCount)")

        let gotChallenge = expectation(description: "got challenge")
        let gotError = expectation(description: "got protocol error")
        let closed = expectation(description: "connection closed")
        closed.assertForOverFulfill = false
        let phone = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { phone.shutdown() }
        phone.onClosed = { closed.fulfill() }
        phone.onEnvelope = { envelope in
            switch envelope.payload {
            case .authChallenge:
                gotChallenge.fulfill()
                var response = DSAuthResponse()
                response.clientNonce = Data(repeating: 0x22, count: 32)
                response.mac = Data(repeating: 0x33, count: 32)
                var unsupported = makeEnvelope(.authResponse(response))
                unsupported.protocolVersion = 1
                phone.send(unsupported)
            case .error(let error) where error.code == "unsupported_protocol_version":
                gotError.fulfill()
            default:
                XCTFail("unexpected envelope")
            }
        }

        try phone.start(host: "127.0.0.1", port: relay.port)
        wait(for: [gotChallenge, gotError, closed], timeout: 10)
    }

    func testReleasesPreviousSessionAfterReconnect() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }

        let identity = try TLSIdentityGenerator.generate()
        let psk = Data(repeating: 0x11, count: 32)
        let authenticated = expectation(description: "authenticated")
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: identity,
            pskProvider: { [psk] },
            relayFingerprint: relay.fingerprint,
            onStateChange: { _ in },
            onClientAuthenticated: { _ in authenticated.fulfill() },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
        defer { transport.stop() }

        transport.start()
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 }, "parked=\(relay.parkedMacCount)")

        let phone = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { phone.shutdown() }
        var handshake = ClientHandshake(psk: psk)
        phone.onEnvelope = { envelope in
            switch handshake.state {
            case .initial:
                if let response = try? handshake.handleChallenge(envelope) { phone.send(response) }
            case .awaitingAuthOk:
                try? handshake.handleAuthOk(envelope)
                phone.send(Self.phoneResume())
            default:
                break
            }
        }

        try phone.start(host: "127.0.0.1", port: relay.port)
        wait(for: [authenticated], timeout: 10)

        let firstSession = WeakBox(transport.lastSessionForTesting)
        XCTAssertNotNil(firstSession.value, "no session was installed")

        phone.cancel()
        XCTAssertTrue(
            waitUntil(timeout: 10) { relay.connectAttempts >= 2 && relay.parkedMacCount == 1 },
            "attempts=\(relay.connectAttempts) parked=\(relay.parkedMacCount)"
        )
        XCTAssertTrue(
            waitUntil(timeout: 5) { firstSession.value == nil },
            "previous session (and its channel/pipeline) was not released after reconnect"
        )
    }

    func testSessionDropsCoalescedFramesAfterVersionViolation() throws {
        let psk = Data(repeating: 0x11, count: 32)
        let session = RelaySessionHandler(psks: [psk])
        let authenticated = expectation(description: "must not authenticate")
        authenticated.isInverted = true
        session.onAuthenticated = { _, _ in authenticated.fulfill() }

        let gate = WriteGateHandler()
        let channel = EmbeddedChannel()
        try channel.pipeline.syncOperations.addHandler(ByteToMessageHandler(RelayFrameDecoderHandler()))
        // Outbound events travel from a handler's context toward the head, so the
        // gate must sit before the session to intercept its writes.
        try channel.pipeline.syncOperations.addHandler(gate)
        try channel.pipeline.syncOperations.addHandler(session)

        channel.pipeline.fireUserInboundEventTriggered(
            TLSUserEvent.handshakeCompleted(negotiatedProtocol: nil)
        )
        channel.embeddedEventLoop.run()
        guard gate.pending.count == 1, let challengeEnvelope = decodeEnvelope(gate.pending[0].buffer) else {
            return XCTFail("expected the auth challenge, pending=\(gate.pending.count)")
        }
        guard case .authChallenge(let challenge) = challengeEnvelope.payload else {
            return XCTFail("expected auth challenge")
        }

        let clientNonce = Data(repeating: 0x22, count: 32)
        var unsupported = DSAuthResponse()
        unsupported.clientNonce = clientNonce
        unsupported.mac = Data(repeating: 0x33, count: 32)
        var bad = makeEnvelope(.authResponse(unsupported))
        bad.protocolVersion = 1

        var valid = DSAuthResponse()
        valid.clientNonce = clientNonce
        valid.minVersion = 2
        valid.maxVersion = 2
        valid.mac = Auth.mac(
            psk: psk,
            role: "client",
            serverNonce: challenge.serverNonce,
            clientNonce: clientNonce
        )

        var inbound = channel.allocator.buffer(capacity: 0)
        inbound.writeBytes(FrameCodec.encode(try bad.serializedData()))
        inbound.writeBytes(FrameCodec.encode(try makeEnvelope(.authResponse(valid)).serializedData()))
        XCTAssertNoThrow(try channel.writeInbound(inbound))

        // The version error's write is still gated, so a handler that only stops
        // reading once the close lands would process the coalesced valid response
        // and send AuthOk (3 pending writes).
        XCTAssertEqual(gate.pending.count, 2, "only the version error may be sent")
        wait(for: [authenticated], timeout: 0.5)

        gate.releaseAll()
        _ = try? channel.finish(acceptAlreadyClosed: true)
    }

    func testLearnsRelayFingerprintOnFirstUse() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: try TLSIdentityGenerator.generate(),
            pskProvider: { [] },
            relayFingerprint: nil,
            onStateChange: { _ in },
            onClientAuthenticated: { _ in },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
        defer { transport.stop() }
        let learned = expectation(description: "fingerprint learned")
        transport.onRelayFingerprintLearned = { fingerprint in
            XCTAssertEqual(fingerprint, relay.fingerprint)
            learned.fulfill()
        }
        transport.start()
        wait(for: [learned], timeout: 5)
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 }, "the join line must travel inside outer TLS")
    }

    func testRelayCertificateMismatchHaltsWithoutRetrying() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: try TLSIdentityGenerator.generate(),
            pskProvider: { [] },
            relayFingerprint: String(repeating: "0", count: 64),
            onStateChange: { _ in },
            onClientAuthenticated: { _ in },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
        defer { transport.stop() }
        let mismatch = expectation(description: "mismatch reported")
        transport.onStateChange = { state in
            if state == .relayCertificateMismatch(relay.fingerprint) { mismatch.fulfill() }
        }
        transport.start()
        wait(for: [mismatch], timeout: 5)
        transport.reconnectIfIdle()
        Thread.sleep(forTimeInterval: 2.5)
        XCTAssertEqual(relay.connectAttempts, 0, "no join line may reach an unpinned relay")
        XCTAssertEqual(transport.state, .relayCertificateMismatch(relay.fingerprint))
    }

    func testUpgradeErrorHaltsWithoutRetrying() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        relay.setMacJoinResponse(Data("ERROR upgrade\n".utf8))
        let transport = try makeTransport(relay: relay)
        defer { transport.stop() }
        let halted = expectation(description: "upgrade required")
        transport.onStateChange = { state in
            if state == .relayRequiresUpgrade { halted.fulfill() }
        }
        transport.start()
        wait(for: [halted], timeout: 5)
        Thread.sleep(forTimeInterval: 2.5)
        XCTAssertEqual(relay.connectAttempts, 1)
        XCTAssertEqual(transport.state, .relayRequiresUpgrade)
    }

    /// Full stack: outer TLS through the in-process relay, inner TLS, PSK auth, negotiation,
    /// Resume, a reliable envelope queued before the phone joined, and capability gating.
    func testQueuedReliableEnvelopeIsDeliveredAfterResumeAndUnnegotiatedPayloadsAreDropped() throws {
        let relay = try InProcessRelay()
        defer { relay.shutdown() }
        let identity = try TLSIdentityGenerator.generate()
        let psk = Data(repeating: 0x11, count: 32)
        let stream = ReliableStream(streamID: "mac-stream")
        let authenticated = expectation(description: "authenticated")
        var session: AuthenticatedSession?
        let transport = RelayTransport(
            host: "127.0.0.1",
            port: relay.port,
            room: Self.room,
            identity: identity,
            pskProvider: { [psk] },
            relayFingerprint: relay.fingerprint,
            reliableStream: stream,
            onStateChange: { _ in },
            onClientAuthenticated: { value in
                session = value
                authenticated.fulfill()
            },
            onClientDisconnected: {},
            onEnvelope: { _ in }
        )
        defer { transport.stop() }
        transport.start()
        XCTAssertTrue(waitUntil { relay.parkedMacCount == 1 })

        var mute = DSMuteListSet()
        mute.packages = ["com.example.queued"]
        transport.send(makeEnvelope(.muteListSet(mute)))
        XCTAssertEqual(stream.pendingCount, 1, "reliable payloads are queued while no phone is connected")

        let phone = try RelayPhoneClient(room: Self.room, identity: identity)
        defer { phone.shutdown() }
        var handshake = ClientHandshake(psk: psk, capabilities: ["notifications", "reliable"])
        var received: [DSEnvelope] = []
        let gotMacResume = expectation(description: "mac resume")
        let gotQueued = expectation(description: "queued mute list")
        let gotLive = expectation(description: "live mute list")
        phone.onEnvelope = { envelope in
            switch handshake.state {
            case .initial:
                if let response = try? handshake.handleChallenge(envelope) { phone.send(response) }
            case .awaitingAuthOk:
                try? handshake.handleAuthOk(envelope)
                phone.send(Self.phoneResume())
            case .authenticated:
                received.append(envelope)
                switch envelope.payload {
                case .resume(let resume)?:
                    XCTAssertEqual(resume.streamID, "mac-stream")
                    gotMacResume.fulfill()
                case .muteListSet(let list)? where list.packages == ["com.example.queued"]:
                    XCTAssertEqual(envelope.seq, 1)
                    gotQueued.fulfill()
                case .muteListSet(let list)? where list.packages == ["com.example.live"]:
                    XCTAssertEqual(envelope.seq, 2)
                    gotLive.fulfill()
                default:
                    break
                }
            case .failed:
                XCTFail("phone handshake failed")
            }
        }
        try phone.start(host: "127.0.0.1", port: relay.port)
        wait(for: [gotMacResume, gotQueued, authenticated], timeout: 10, enforceOrder: false)
        XCTAssertEqual(session?.negotiation, Negotiation(version: 2, capabilities: ["notifications", "reliable"]))
        XCTAssertEqual(stream.counters.retransmits, 1)

        var dial = DSDialRequest()
        dial.number = "+15550000000"
        transport.send(makeEnvelope(.dialRequest(dial)))
        mute.packages = ["com.example.live"]
        transport.send(makeEnvelope(.muteListSet(mute)))
        wait(for: [gotLive], timeout: 5)
        XCTAssertFalse(
            received.contains { if case .dialRequest? = $0.payload { return true } else { return false } },
            "calls was not negotiated"
        )

        // The phone acknowledges everything; the outbox drains.
        var ack = makeEnvelope(.ackOnly(DSAck()))
        ack.ack = 2
        phone.send(ack)
        XCTAssertTrue(waitUntil { stream.pendingCount == 0 }, "pending=\(stream.pendingCount)")
    }

    static func phoneResume(streamID: String = "phone-stream") -> DSEnvelope {
        var resume = DSResume()
        resume.streamID = streamID
        return makeEnvelope(.resume(resume))
    }

    private func decodeEnvelope(_ buffer: ByteBuffer) -> DSEnvelope? {
        var decoder = FrameDecoder()
        guard let frame = try? decoder.append(Data(buffer.readableBytesView)).first else { return nil }
        return try? DSEnvelope(serializedBytes: frame)
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

private final class WeakBox {
    weak var value: RelaySessionHandler?

    init(_ value: RelaySessionHandler?) {
        self.value = value
    }
}

/// Intercepts outbound writes and holds their promises until `releaseAll()`, so a
/// test can inspect what the session tried to send before a close completes.
private final class WriteGateHandler: ChannelOutboundHandler {
    typealias OutboundIn = ByteBuffer
    typealias OutboundOut = ByteBuffer

    private(set) var pending: [(buffer: ByteBuffer, promise: EventLoopPromise<Void>?)] = []
    private weak var context: ChannelHandlerContext?

    func write(context: ChannelHandlerContext, data: NIOAny, promise: EventLoopPromise<Void>?) {
        self.context = context
        pending.append((unwrapOutboundIn(data), promise))
    }

    func releaseAll() {
        guard let context else { return }
        let items = pending
        pending.removeAll()
        for item in items {
            context.write(wrapOutboundOut(item.buffer), promise: item.promise)
        }
        context.flush()
    }
}
