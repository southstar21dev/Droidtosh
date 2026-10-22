package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Envelope.PayloadCase
import dev.southstar21.droidtosh.protocol.v1.Resume
import java.util.UUID

/**
 * Reliable delivery and resume (protocol/PROTOCOL-v2.md §4–5). One instance per service start: its
 * [streamId] lives as long as the instance, and the outbox and receive state carry across reconnects
 * so unacknowledged envelopes are retransmitted after the next [onPeerResume].
 *
 * Thread-safe: the writer, the reader loop and the ack timer all call in concurrently.
 */
class ReliableStream(
    private val clock: () -> Long = System::currentTimeMillis,
    val streamId: String = UUID.randomUUID().toString(),
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
    private val ttlMs: Long = TTL_MS,
    private val ackDelayMs: Long = ACK_DELAY_MS,
) {
    enum class Decision { PROCESS, DUPLICATE }

    data class Stats(
        val outboxSize: Int,
        val evicted: Long,
        val expired: Long,
        val gaps: Long,
        val duplicates: Long,
        val retransmits: Long,
    )

    private class Entry(val envelope: Envelope, val size: Int, val enqueuedAt: Long)

    private var nextSeq = 0L
    private val outbox = ArrayDeque<Entry>()
    private var outboxBytes = 0L

    private var peerStreamId: String? = null
    private var lastProcessed = 0L
    private var ackPendingSince: Long? = null

    // Acks on incoming envelopes only refer to our current stream once the peer has sent its Resume
    // for this session (it sends nothing else before receiving ours, §5).
    private var peerResumed = false

    private var evicted = 0L
    private var expired = 0L
    private var gaps = 0L
    private var duplicates = 0L
    private var retransmits = 0L

    /** Call when a new connection is authenticated, before sending [resume]. */
    @Synchronized
    fun beginSession() {
        peerResumed = false
        ackPendingSince = null
    }

    @Synchronized
    fun resume(): Resume = Resume.newBuilder()
        .setStreamId(streamId)
        .setPeerStreamId(peerStreamId ?: "")
        .setLastReceivedSeq(lastProcessed)
        .build()

    /**
     * Stamps [envelope] for sending: a reliable payload gets the next seq and is kept in the outbox
     * until acknowledged; every envelope carries the current ack.
     */
    @Synchronized
    fun prepareOutgoing(envelope: Envelope): Envelope {
        expire()
        val builder = envelope.toBuilder().setAck(lastProcessed)
        if (isReliable(envelope) && envelope.seq == 0L) {
            nextSeq += 1
            builder.setSeq(nextSeq)
            val stamped = builder.build()
            remember(stamped)
            ackPendingSince = null
            return stamped
        }
        ackPendingSince = null
        return builder.build()
    }

    /** Applies the ack and seq of a received envelope; [Decision.DUPLICATE] means don't process it. */
    @Synchronized
    fun onReceive(envelope: Envelope): Decision {
        if (peerResumed && !envelope.hasResume()) trimAcked(envelope.ack)
        val seq = envelope.seq
        if (seq == 0L) return Decision.PROCESS
        if (ackPendingSince == null) ackPendingSince = clock()
        return when {
            seq <= lastProcessed -> {
                duplicates += 1
                Decision.DUPLICATE
            }
            seq == lastProcessed + 1 -> {
                lastProcessed = seq
                Decision.PROCESS
            }
            else -> {
                gaps += 1
                lastProcessed = seq
                Decision.PROCESS
            }
        }
    }

    /**
     * Handles the peer's [Resume] and returns the outbox entries to retransmit, in seq order, with a
     * refreshed ack. Send them as-is (they already carry their seq).
     */
    @Synchronized
    fun onPeerResume(resume: Resume): List<Envelope> {
        if (resume.streamId != peerStreamId) {
            peerStreamId = resume.streamId
            lastProcessed = 0
        }
        expire()
        if (resume.peerStreamId == streamId) trimAcked(resume.lastReceivedSeq)
        peerResumed = true
        retransmits += outbox.size
        return outbox.map { it.envelope.toBuilder().setAck(lastProcessed).build() }
    }

    /** Whether a standalone ack is owed (something reliable arrived and no envelope carried its ack). */
    @Synchronized
    fun ackDue(): Boolean {
        val since = ackPendingSince ?: return false
        return clock() - since >= ackDelayMs
    }

    @Synchronized
    fun stats(): Stats = Stats(outbox.size, evicted, expired, gaps, duplicates, retransmits)

    private fun remember(envelope: Envelope) {
        val size = envelope.serializedSize
        outbox.addLast(Entry(envelope, size, clock()))
        outboxBytes += size
        while (outbox.size > maxEntries || outboxBytes > maxBytes) {
            removeFirst()
            evicted += 1
        }
    }

    private fun trimAcked(ack: Long) {
        while (outbox.isNotEmpty() && outbox.first().envelope.seq <= ack) removeFirst()
    }

    private fun expire() {
        val now = clock()
        while (outbox.isNotEmpty() && now - outbox.first().enqueuedAt > ttlMs) {
            removeFirst()
            expired += 1
        }
    }

    private fun removeFirst() {
        outboxBytes -= outbox.removeFirst().size
    }

    companion object {
        const val MAX_ENTRIES = 256
        const val MAX_BYTES = 4L * 1024 * 1024
        const val TTL_MS = 120_000L
        const val ACK_DELAY_MS = 1_000L

        /** Payloads that must survive a reconnect (§4.3, §7.1, §9); the phone only sends the phone → Mac ones. */
        private val RELIABLE = setOf(
            PayloadCase.ACTION_RESULT,
            PayloadCase.CLIPBOARD_TEXT,
            PayloadCase.FILE_OFFER,
            PayloadCase.FILE_ACCEPT,
            PayloadCase.FILE_REJECT,
            PayloadCase.FILE_END,
            PayloadCase.FILE_COMPLETE,
            PayloadCase.FILE_CANCEL,
            PayloadCase.FIND_PHONE_REQUEST,
            PayloadCase.FIND_PHONE_STATE,
        )

        fun isReliable(envelope: Envelope): Boolean = envelope.payloadCase in RELIABLE
    }
}
