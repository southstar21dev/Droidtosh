package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.ActionResult
import dev.southstar21.droidtosh.protocol.v1.ClipboardText
import dev.southstar21.droidtosh.protocol.v1.DeviceStatus
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindPhoneRequest
import dev.southstar21.droidtosh.protocol.v1.FindPhoneState
import dev.southstar21.droidtosh.protocol.v1.NotificationDismiss
import dev.southstar21.droidtosh.protocol.v1.Ping
import dev.southstar21.droidtosh.protocol.v1.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableStreamTest {
    private var now = 0L
    private fun stream(
        maxEntries: Int = ReliableStream.MAX_ENTRIES,
        maxBytes: Long = ReliableStream.MAX_BYTES,
    ) = ReliableStream(clock = { now }, streamId = "phone-1", maxEntries = maxEntries, maxBytes = maxBytes)

    private fun result(id: String) = newEnvelope { setActionResult(ActionResult.newBuilder().setRequestId(id)) }
    private fun ping() = newEnvelope { setPing(Ping.getDefaultInstance()) }
    private fun incoming(seq: Long, ack: Long = 0) = newEnvelope {
        setNotificationDismiss(NotificationDismiss.newBuilder().setKey("k"))
        setSeq(seq)
        setAck(ack)
    }
    private fun resume(streamId: String, peerStreamId: String = "", last: Long = 0) =
        Resume.newBuilder().setStreamId(streamId).setPeerStreamId(peerStreamId).setLastReceivedSeq(last).build()
    private fun peerResumed(s: ReliableStream, last: Long = 0) = s.onPeerResume(resume("mac-1", "phone-1", last))

    @Test
    fun reliablePayloadsGetIncreasingSeqOthersZero() {
        val s = stream()
        assertEquals(1L, s.prepareOutgoing(result("a")).seq)
        assertEquals(0L, s.prepareOutgoing(ping()).seq)
        assertEquals(2L, s.prepareOutgoing(newEnvelope { setClipboardText(ClipboardText.newBuilder().setText("x")) }).seq)
        assertEquals(2, s.stats().outboxSize)
    }

    @Test
    fun outgoingCarriesAckOfHighestProcessed() {
        val s = stream()
        s.onReceive(incoming(1))
        s.onReceive(incoming(2))
        assertEquals(2L, s.prepareOutgoing(ping()).ack)
    }

    @Test
    fun ackTrimsOutboxOnlyAfterPeerResume() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        s.prepareOutgoing(result("b"))
        s.onReceive(incoming(seq = 0, ack = 2))
        assertEquals(2, s.stats().outboxSize)
        peerResumed(s)
        s.onReceive(incoming(seq = 0, ack = 1))
        assertEquals(1, s.stats().outboxSize)
    }

    @Test
    fun duplicatesAreDroppedAndGapsCounted() {
        val s = stream()
        assertEquals(ReliableStream.Decision.PROCESS, s.onReceive(incoming(1)))
        assertEquals(ReliableStream.Decision.DUPLICATE, s.onReceive(incoming(1)))
        assertEquals(ReliableStream.Decision.PROCESS, s.onReceive(incoming(4)))
        assertEquals(ReliableStream.Decision.DUPLICATE, s.onReceive(incoming(3)))
        assertEquals(2L, s.stats().duplicates)
        assertEquals(1L, s.stats().gaps)
        assertEquals(ReliableStream.Decision.PROCESS, s.onReceive(ping()))
    }

    @Test
    fun standaloneAckDueAfterDelayUnlessPiggybacked() {
        val s = stream()
        s.onReceive(incoming(1))
        assertFalse(s.ackDue())
        now += 999
        assertFalse(s.ackDue())
        now += 1
        assertTrue(s.ackDue())
        s.prepareOutgoing(ping())
        assertFalse(s.ackDue())
    }

    @Test
    fun duplicateStillOwesAnAck() {
        val s = stream()
        s.onReceive(incoming(1))
        s.prepareOutgoing(ping())
        s.onReceive(incoming(1))
        now += 1_000
        assertTrue(s.ackDue())
    }

    @Test
    fun resumeFromSamePeerStreamRetransmitsUnackedInOrder() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        s.prepareOutgoing(result("b"))
        s.prepareOutgoing(result("c"))
        val retransmit = peerResumed(s, last = 1)
        assertEquals(listOf(2L, 3L), retransmit.map { it.seq })
        assertEquals(listOf("b", "c"), retransmit.map { it.actionResult.requestId })
        assertEquals(2L, s.stats().retransmits)
    }

    @Test
    fun resumeAfterPeerLostOurStreamRetransmitsEverything() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        s.prepareOutgoing(result("b"))
        val retransmit = s.onPeerResume(resume("mac-1", peerStreamId = "someone-else", last = 5))
        assertEquals(listOf(1L, 2L), retransmit.map { it.seq })
    }

    @Test
    fun retransmittedEnvelopesKeepTheirSeqWhenSentAgain() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        val again = s.prepareOutgoing(peerResumed(s).single())
        assertEquals(1L, again.seq)
        assertEquals(1, s.stats().outboxSize)
    }

    @Test
    fun newPeerStreamResetsReceiveState() {
        val s = stream()
        s.onPeerResume(resume("mac-1"))
        s.onReceive(incoming(1))
        s.onReceive(incoming(2))
        s.onPeerResume(resume("mac-2"))
        assertEquals(ReliableStream.Decision.PROCESS, s.onReceive(incoming(1)))
        assertEquals("mac-2", s.resume().peerStreamId)
        assertEquals(1L, s.resume().lastReceivedSeq)
    }

    @Test
    fun samePeerStreamKeepsReceiveStateAcrossReconnects() {
        val s = stream()
        s.onPeerResume(resume("mac-1"))
        s.onReceive(incoming(1))
        s.beginSession()
        s.onPeerResume(resume("mac-1"))
        assertEquals(ReliableStream.Decision.DUPLICATE, s.onReceive(incoming(1)))
        assertEquals(1L, s.resume().lastReceivedSeq)
    }

    @Test
    fun resumeAdvertisesOwnAndPeerStream() {
        val s = stream()
        assertEquals("phone-1", s.resume().streamId)
        assertEquals("", s.resume().peerStreamId)
        assertEquals(0L, s.resume().lastReceivedSeq)
    }

    @Test
    fun outboxEvictsOldestBeyondEntryCap() {
        val s = stream(maxEntries = 2)
        s.prepareOutgoing(result("a"))
        s.prepareOutgoing(result("b"))
        s.prepareOutgoing(result("c"))
        assertEquals(listOf(2L, 3L), peerResumed(s).map { it.seq })
        assertEquals(1L, s.stats().evicted)
    }

    @Test
    fun outboxEvictsOldestBeyondByteCap() {
        val first = result("a")
        val s = stream(maxBytes = first.serializedSize + 5L)
        s.prepareOutgoing(first)
        s.prepareOutgoing(result("b"))
        assertEquals(1, s.stats().outboxSize)
        assertEquals(1L, s.stats().evicted)
    }

    @Test
    fun entriesExpireAfterTtl() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        now += ReliableStream.TTL_MS
        s.prepareOutgoing(result("b"))
        now += 1
        val retransmit = peerResumed(s)
        assertEquals(listOf(2L), retransmit.map { it.seq })
        assertEquals(1L, s.stats().expired)
    }

    @Test
    fun resumeEnvelopeAckIsIgnored() {
        val s = stream()
        s.prepareOutgoing(result("a"))
        peerResumed(s)
        s.onReceive(newEnvelope { setResume(resume("mac-1")); setAck(9) })
        assertEquals(1, s.stats().outboxSize)
    }

    @Test
    fun onlyActionResultAndClipboardTextAreReliableFromThePhone() {
        assertTrue(ReliableStream.isReliable(result("a")))
        assertTrue(ReliableStream.isReliable(newEnvelope { setClipboardText(ClipboardText.getDefaultInstance()) }))
        assertFalse(ReliableStream.isReliable(ping()))
        assertFalse(ReliableStream.isReliable(Envelope.getDefaultInstance()))
    }

    @Test
    fun findStateIsReliableAndDeviceStatusIsNot() {
        assertTrue(ReliableStream.isReliable(newEnvelope { setFindPhoneState(FindPhoneState.getDefaultInstance()) }))
        assertTrue(ReliableStream.isReliable(newEnvelope { setFindPhoneRequest(FindPhoneRequest.getDefaultInstance()) }))
        assertFalse(ReliableStream.isReliable(newEnvelope { setDeviceStatus(DeviceStatus.getDefaultInstance()) }))
    }
}
