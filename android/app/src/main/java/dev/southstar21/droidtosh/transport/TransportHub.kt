package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object TransportHub {
    // Inbound envelopes are routed into one bounded inbox per feature instead of a single broadcast,
    // so a slow feature only fills its own inbox. The reader never suspends: when an inbox is full the
    // envelope is dropped and counted (see [droppedInbound]). The clipboard inbox holds more than one
    // maximum-size image transfer (32 MB / 256 KB chunks = 128 chunks + start/end), so a transfer is
    // never cut short just because the clipboard handler is busy decoding the previous one.
    // Inboxes buffer until their feature subscribes (e.g. the notification listener binding late), and
    // are cleared when a new session attaches so requests from a previous session aren't replayed.
    private val inboxes: Map<InboundFeature, Channel<Envelope>> =
        InboundFeature.entries.associateWith { Channel<Envelope>(capacity = it.capacity) }
    private val drops: Map<InboundFeature, AtomicLong> =
        InboundFeature.entries.associateWith { AtomicLong() }

    /** Envelopes routed to [feature]. Each envelope is delivered to exactly one collector. */
    fun incoming(feature: InboundFeature): Flow<Envelope> = inboxes.getValue(feature).receiveAsFlow()

    /** Envelopes dropped so far because [feature]'s inbox was full. */
    fun droppedInbound(feature: InboundFeature): Long = drops.getValue(feature).get()

    private val mutableConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbound = Channel<Envelope>(capacity = 256)
    private var writerJob: Job? = null

    @Volatile
    private var client: ProtocolClient? = null

    // The reliable stream outlives sessions (one per service start), so reliable envelopes sent while
    // disconnected wait in its outbox and are retransmitted after the next Resume.
    @Volatile
    private var reliable: ReliableStream? = null

    private val mutableCapabilities = MutableStateFlow<Set<String>>(emptySet())

    /** Capabilities negotiated with the Mac for the current session; empty while disconnected. */
    val capabilities: StateFlow<Set<String>> = mutableCapabilities.asStateFlow()

    /** A session whose feature traffic flows (Resume exchanged, §5); [id] is new for every session. */
    data class ReadySession(val id: Long, val capabilities: Set<String>)

    private val sessionIds = AtomicLong()
    private val mutableReadySession = MutableStateFlow<ReadySession?>(null)

    /**
     * The current session once its feature envelopes are flowing, or null. Features that send on every
     * session (e.g. phone status after Resume) key off a change of [ReadySession.id], so a quick
     * reconnect a collector didn't see go down still counts as a new session.
     */
    val readySession: StateFlow<ReadySession?> = mutableReadySession.asStateFlow()

    /** Delivery counters of the current reliable stream, for diagnostics. */
    fun reliableStats(): ReliableStream.Stats? = reliable?.stats()

    /**
     * Attaches a session. With [ready] false the writer doesn't start, so feature envelopes queue until
     * [markReady] (after the Resume exchange, protocol/PROTOCOL-v2.md §5); the session itself still
     * writes directly through [client].
     */
    @Synchronized
    fun attach(
        client: ProtocolClient,
        stream: ReliableStream? = null,
        capabilities: Set<String> = Capabilities.SUPPORTED.toSet(),
        ready: Boolean = true,
    ) {
        writerJob?.cancel()
        writerJob = null
        inboxes.values.forEach { inbox ->
            while (inbox.tryReceive().isSuccess) {
            }
        }
        this.client = client
        reliable = stream
        mutableReadySession.value = null
        mutableCapabilities.value = capabilities
        mutableConnected.value = true
        if (ready) startWriter(client)
    }

    /** Forgets [stream] when its service stops, so nothing more is held for a stream that's gone. */
    @Synchronized
    fun releaseStream(stream: ReliableStream) {
        if (reliable === stream) reliable = null
    }

    /** Starts flushing queued feature envelopes to [owner], if it is still the attached client. */
    @Synchronized
    fun markReady(owner: ProtocolClient) {
        if (client === owner && writerJob == null) startWriter(owner)
    }

    private fun startWriter(client: ProtocolClient) {
        writerJob = scope.launch {
            for (envelope in outbound) {
                runCatching { client.send(envelope) }
            }
        }
        mutableReadySession.value = ReadySession(sessionIds.incrementAndGet(), mutableCapabilities.value)
    }

    /** Detaches [owner] only if it is still the attached client, so a stale session can't tear down a newer one. */
    @Synchronized
    fun detach(owner: ProtocolClient) {
        if (client === owner) detach()
    }

    @Synchronized
    fun detach() {
        writerJob?.cancel()
        writerJob = null
        client = null
        mutableReadySession.value = null
        mutableConnected.value = false
        mutableCapabilities.value = emptySet()
        // Queued reliable envelopes move into the outbox so the next session retransmits them.
        val stream = reliable
        while (true) {
            val envelope = outbound.tryReceive().getOrNull() ?: break
            if (stream != null && ReliableStream.isReliable(envelope)) stream.prepareOutgoing(envelope)
        }
    }

    fun send(envelope: Envelope): Boolean {
        val required = Capabilities.requiredFor(envelope)
        if (client == null) return holdWhileDisconnected(envelope)
        if (required != null && required !in mutableCapabilities.value) return false
        return outbound.trySend(envelope).isSuccess
    }

    /** Like [send], but waits for queue space instead of dropping, for multi-envelope transfers. */
    suspend fun sendAwait(envelope: Envelope): Boolean {
        val required = Capabilities.requiredFor(envelope)
        if (client == null) return holdWhileDisconnected(envelope)
        if (required != null && required !in mutableCapabilities.value) return false
        outbound.send(envelope)
        return true
    }

    private fun holdWhileDisconnected(envelope: Envelope): Boolean {
        val stream = reliable ?: return false
        if (!ReliableStream.isReliable(envelope)) return false
        stream.prepareOutgoing(envelope)
        return true
    }

    /**
     * Routes [envelope] to its feature's inbox without blocking. Returns false if it has no feature
     * (connection-level payloads are handled by the session itself) or its inbox was full.
     */
    fun dispatch(envelope: Envelope): Boolean {
        val feature = InboundFeature.of(envelope) ?: return false
        Diagnostics.recorder.onInbound(feature)
        if (inboxes.getValue(feature).trySend(envelope).isSuccess) return true
        val dropped = drops.getValue(feature).incrementAndGet()
        TransportLog.w("DroidsyncHub", "$feature inbox full, dropped ${envelope.payloadCase} (total $dropped)")
        return false
    }
}
