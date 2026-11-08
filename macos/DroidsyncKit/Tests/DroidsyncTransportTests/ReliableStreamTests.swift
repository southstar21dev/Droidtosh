import XCTest
import DroidsyncProtocol
@testable import DroidsyncTransport

final class ReliableStreamTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    private func action(_ key: String = "k") -> DSEnvelope {
        var action = DSNotificationAction()
        action.key = key
        return protocolEnvelope(.notificationAction(action))
    }

    private func inbound(seq: UInt64, ack: UInt64 = 0) -> DSEnvelope {
        var envelope = protocolEnvelope(.actionResult(DSActionResult()))
        envelope.seq = seq
        envelope.ack = ack
        return envelope
    }

    private func resume(stream: String, peer: String, last: UInt64) -> DSResume {
        var resume = DSResume()
        resume.streamID = stream
        resume.peerStreamID = peer
        resume.lastReceivedSeq = last
        return resume
    }

    func testAssignsIncreasingSeqOnlyToReliableEnvelopes() {
        let stream = ReliableStream(streamID: "mac")
        XCTAssertEqual(stream.stamp(action(), reliable: true, now: t0).seq, 1)
        XCTAssertEqual(stream.stamp(protocolEnvelope(.ping(DSPing())), reliable: false, now: t0).seq, 0)
        XCTAssertEqual(stream.stamp(action(), reliable: true, now: t0).seq, 2)
        XCTAssertEqual(stream.pendingCount, 2)
    }

    func testPiggybacksAckAndDropsAcknowledgedEntries() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.applyPeerResume(resume(stream: "phone", peer: "", last: 0), now: t0)
        for _ in 0..<3 { _ = stream.stamp(action(), reliable: true, now: t0) }
        XCTAssertEqual(stream.receive(inbound(seq: 1, ack: 2), now: t0), .process)
        XCTAssertEqual(stream.pendingCount, 1)
        XCTAssertEqual(stream.stamp(protocolEnvelope(.ping(DSPing())), reliable: false, now: t0).ack, 1)
    }

    func testAckBeforePeerResumeCanBeIgnored() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.stamp(action(), reliable: true, now: t0)
        XCTAssertEqual(stream.receive(inbound(seq: 0, ack: 5), applyAck: false, now: t0), .process)
        XCTAssertEqual(stream.pendingCount, 1)
        XCTAssertEqual(stream.receive(inbound(seq: 0, ack: 1), now: t0), .process)
        XCTAssertEqual(stream.pendingCount, 0)
    }

    func testDropsDuplicatesButStillOwesAnAck() {
        let stream = ReliableStream(streamID: "mac")
        XCTAssertEqual(stream.receive(inbound(seq: 1), now: t0), .process)
        _ = stream.stamp(protocolEnvelope(.ping(DSPing())), reliable: false, now: t0)
        XCTAssertEqual(stream.receive(inbound(seq: 1), now: t0), .duplicate)
        XCTAssertEqual(stream.counters.duplicates, 1)
        XCTAssertNotNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(1)))
    }

    func testUnreliableInboundIsAlwaysProcessedAndOwesNoAck() {
        let stream = ReliableStream(streamID: "mac")
        XCTAssertEqual(stream.receive(inbound(seq: 0), now: t0), .process)
        XCTAssertEqual(stream.receive(inbound(seq: 0), now: t0), .process)
        XCTAssertNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(5)))
    }

    func testCountsGapsAndAdvances() {
        let stream = ReliableStream(streamID: "mac")
        XCTAssertEqual(stream.receive(inbound(seq: 1), now: t0), .process)
        XCTAssertEqual(stream.receive(inbound(seq: 4), now: t0), .process)
        XCTAssertEqual(stream.counters.gaps, 2)
        XCTAssertEqual(stream.lastProcessedSeq, 4)
        XCTAssertEqual(stream.receive(inbound(seq: 3), now: t0), .duplicate)
    }

    func testStandaloneAckAfterOneSecondUnlessPiggybacked() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.receive(inbound(seq: 1), now: t0)
        XCTAssertNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(0.5)))
        let ack = stream.standaloneAckIfDue(now: t0.addingTimeInterval(1))
        XCTAssertEqual(ack?.ack, 1)
        XCTAssertEqual(ack?.seq, 0)
        guard case .ackOnly? = ack?.payload else { return XCTFail("expected ack_only") }
        XCTAssertNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(2)), "ack is sent once")

        _ = stream.receive(inbound(seq: 2), now: t0)
        _ = stream.stamp(action(), reliable: true, now: t0)
        XCTAssertNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(3)), "piggybacked ack clears the debt")
    }

    func testQueuedButUntransmittedEnvelopeKeepsTheAckOwed() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.receive(inbound(seq: 1), now: t0)
        _ = stream.stamp(action(), reliable: true, transmitted: false, now: t0)
        XCTAssertNotNil(stream.standaloneAckIfDue(now: t0.addingTimeInterval(1)))
    }

    func testEvictsOldestWhenEntryCapReached() {
        let stream = ReliableStream(streamID: "mac", maxEntries: 3)
        for index in 0..<5 { _ = stream.stamp(action("k\(index)"), reliable: true, now: t0) }
        XCTAssertEqual(stream.pendingCount, 3)
        XCTAssertEqual(stream.counters.evicted, 2)
        let resent = stream.applyPeerResume(resume(stream: "phone", peer: "other", last: 0), now: t0)
        XCTAssertEqual(resent.map(\.seq), [3, 4, 5])
    }

    func testEvictsOldestWhenByteCapReached() {
        let stream = ReliableStream(streamID: "mac", maxBytes: 300)
        var big = DSClipboardText()
        big.text = String(repeating: "x", count: 120)
        for _ in 0..<4 { _ = stream.stamp(protocolEnvelope(.clipboardText(big)), reliable: true, now: t0) }
        XCTAssertLessThan(stream.pendingCount, 4)
        XCTAssertGreaterThan(stream.counters.evicted, 0)
    }

    func testDefaultCapsMatchTheSpec() {
        XCTAssertEqual(ReliableStream.maxEntries, 256)
        XCTAssertEqual(ReliableStream.maxBytes, 4 * 1024 * 1024)
        XCTAssertEqual(ReliableStream.ttl, 120)
    }

    func testExpiresEntriesAfterTTL() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.stamp(action(), reliable: true, now: t0)
        _ = stream.stamp(action(), reliable: true, now: t0.addingTimeInterval(100))
        let resent = stream.applyPeerResume(
            resume(stream: "phone", peer: "mac", last: 0), now: t0.addingTimeInterval(121)
        )
        XCTAssertEqual(resent.map(\.seq), [2])
        XCTAssertEqual(stream.counters.expired, 1)
    }

    func testResumeWithSameStreamRetransmitsOnlyUnacknowledged() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.applyPeerResume(resume(stream: "phone", peer: "", last: 0), now: t0)
        _ = stream.receive(inbound(seq: 1), now: t0)
        _ = stream.receive(inbound(seq: 2), now: t0)
        for index in 0..<4 { _ = stream.stamp(action("k\(index)"), reliable: true, now: t0) }

        // Reconnect: the phone processed our 1 and 2 and kept its stream.
        let ourResume = stream.makeResume()
        XCTAssertEqual(ourResume.resume.streamID, "mac")
        XCTAssertEqual(ourResume.resume.peerStreamID, "phone")
        XCTAssertEqual(ourResume.resume.lastReceivedSeq, 2)

        let resent = stream.applyPeerResume(resume(stream: "phone", peer: "mac", last: 2), now: t0)
        XCTAssertEqual(resent.map(\.seq), [3, 4])
        XCTAssertEqual(resent.map(\.notificationAction.key), ["k2", "k3"])
        XCTAssertTrue(resent.allSatisfy { $0.ack == 2 })
        XCTAssertEqual(stream.counters.retransmits, 2)
        XCTAssertEqual(stream.lastProcessedSeq, 2, "same peer stream keeps receive state")
        XCTAssertEqual(stream.receive(inbound(seq: 2), now: t0), .duplicate)
    }

    func testResumeFromRestartedPeerResetsReceiveStateAndResendsEverything() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.applyPeerResume(resume(stream: "phone-1", peer: "", last: 0), now: t0)
        _ = stream.receive(inbound(seq: 1), now: t0)
        _ = stream.receive(inbound(seq: 2), now: t0)
        _ = stream.stamp(action("a"), reliable: true, now: t0)
        _ = stream.stamp(action("b"), reliable: true, now: t0)

        let resent = stream.applyPeerResume(resume(stream: "phone-2", peer: "", last: 0), now: t0)
        XCTAssertEqual(resent.map(\.seq), [1, 2])
        XCTAssertTrue(resent.allSatisfy { $0.ack == 0 })
        XCTAssertEqual(stream.lastProcessedSeq, 0)
        XCTAssertEqual(stream.receive(inbound(seq: 1), now: t0), .process, "new peer stream starts at 1")
    }

    func testResumeNamingAnotherStreamOfOursResendsEverything() {
        let stream = ReliableStream(streamID: "mac-new")
        _ = stream.stamp(action("a"), reliable: true, now: t0)
        let resent = stream.applyPeerResume(resume(stream: "phone", peer: "mac-old", last: 9), now: t0)
        XCTAssertEqual(resent.map(\.seq), [1], "an ack for a previous Mac process must not drop our entries")
    }

    func testRetransmitsKeepOriginalOrderAcrossRepeatedResumes() {
        let stream = ReliableStream(streamID: "mac")
        for index in 0..<3 { _ = stream.stamp(action("k\(index)"), reliable: true, now: t0) }
        let first = stream.applyPeerResume(resume(stream: "phone", peer: "mac", last: 0), now: t0)
        let second = stream.applyPeerResume(resume(stream: "phone", peer: "mac", last: 1), now: t0)
        XCTAssertEqual(first.map(\.seq), [1, 2, 3])
        XCTAssertEqual(second.map(\.seq), [2, 3])
    }

    // MARK: Withdrawing

    private func keyOf(_ envelope: DSEnvelope) -> String? {
        guard case .notificationAction(let action)? = envelope.payload else { return nil }
        return action.key
    }

    func testWithdrawsAQueuedEntryAndRenumbersTheOnesAfterIt() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.stamp(action("a"), reliable: true, transmitted: false, now: t0)
        _ = stream.stamp(action("b"), reliable: true, transmitted: false, now: t0)
        _ = stream.stamp(action("c"), reliable: true, transmitted: false, now: t0)
        XCTAssertTrue(stream.withdrawUnsent { self.keyOf($0) == "b" })
        XCTAssertEqual(stream.pendingCount, 2)
        let sent = stream.applyPeerResume(resume(stream: "phone", peer: "", last: 0), now: t0)
        XCTAssertEqual(sent.map(keyOf), ["a", "c"])
        XCTAssertEqual(sent.map(\.seq), [1, 2])
        // The next envelope continues the sequence without a gap.
        XCTAssertEqual(stream.stamp(action("d"), reliable: true, now: t0).seq, 3)
    }

    func testCannotWithdrawATransmittedEntry() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.applyPeerResume(resume(stream: "phone", peer: "", last: 0), now: t0)
        _ = stream.stamp(action("sent"), reliable: true, now: t0)
        XCTAssertFalse(stream.withdrawUnsent { self.keyOf($0) == "sent" })
        XCTAssertEqual(stream.pendingCount, 1)
    }

    func testCannotWithdrawAnEntryRetransmittedOnResume() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.stamp(action("queued"), reliable: true, transmitted: false, now: t0)
        // The Resume wrote it, though the connection then died before an ack.
        XCTAssertEqual(stream.applyPeerResume(resume(stream: "phone", peer: "", last: 0), now: t0).count, 1)
        XCTAssertFalse(stream.withdrawUnsent { self.keyOf($0) == "queued" })
        XCTAssertEqual(stream.applyPeerResume(resume(stream: "phone", peer: "mac", last: 0), now: t0).map(keyOf), ["queued"])
    }

    func testWithdrawWithoutAMatchChangesNothing() {
        let stream = ReliableStream(streamID: "mac")
        _ = stream.stamp(action("a"), reliable: true, transmitted: false, now: t0)
        XCTAssertFalse(stream.withdrawUnsent { self.keyOf($0) == "zzz" })
        XCTAssertEqual(stream.stamp(action("b"), reliable: true, transmitted: false, now: t0).seq, 2)
    }
}
