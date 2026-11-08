package dev.southstar21.droidtosh.diagnostics

import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.ProtocolVersionException
import dev.southstar21.droidtosh.transport.RelayTlsException
import dev.southstar21.droidtosh.transport.ReliableStream
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

/** Process-wide recorder the transport and features report into; see [DiagnosticsRecorder]. */
object Diagnostics {
    val recorder = DiagnosticsRecorder()
}

/** Why a session ended or an attempt failed; the reconnect that follows is counted under it. */
enum class DisconnectReason(val label: String) {
    NETWORK_CHANGE("network change"),
    NETWORK_ERROR("network error"),
    TIMEOUT("read timeout"),
    CONNECTION_CLOSED("connection closed"),
    RELAY_TLS("relay TLS / pin"),
    MAC_TLS("Mac TLS / pin"),
    RELAY_ERROR("relay error"),
    RELAY_WAIT("Mac not at relay"),
    MAC_ASLEEP("Mac asleep"),
    PROTOCOL("protocol error"),
    RESUME_TIMEOUT("resume timeout"),
    MANUAL("reconnect requested"),
    OTHER("other"),
    ;

    companion object {
        fun classify(error: Throwable): DisconnectReason = when (error) {
            is RelayTlsException -> RELAY_TLS
            is SSLException -> MAC_TLS
            is ProtocolVersionException, is IllegalStateException, is IllegalArgumentException -> PROTOCOL
            is SocketTimeoutException -> TIMEOUT
            is EOFException -> CONNECTION_CLOSED
            is UnknownHostException -> NETWORK_ERROR
            is java.io.IOException ->
                if (error.message.orEmpty().startsWith("relay error") ||
                    error.message.orEmpty().startsWith("Relay and app")
                ) RELAY_ERROR else NETWORK_ERROR
            else -> OTHER
        }
    }
}

enum class EventKind { STATE, ERROR, NETWORK, PUSH, INFO }

data class DiagnosticEvent(val atMs: Long, val kind: EventKind, val message: String)

/** Fixed-size, thread-safe ring buffer of the most recent events, oldest first. */
class EventLog(private val capacity: Int = 20) {
    private val events = ArrayDeque<DiagnosticEvent>(capacity)

    @Synchronized
    fun add(event: DiagnosticEvent) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()
}

data class RttSummary(val lastMs: Long, val avgMs: Long, val minMs: Long, val maxMs: Long, val samples: Long)

class RttStats {
    private var last = 0L
    private var sum = 0L
    private var count = 0L
    private var min = Long.MAX_VALUE
    private var max = 0L

    @Synchronized
    fun record(rttMs: Long) {
        if (rttMs < 0) return
        last = rttMs
        sum += rttMs
        count += 1
        if (rttMs < min) min = rttMs
        if (rttMs > max) max = rttMs
    }

    @Synchronized
    fun reset() {
        last = 0; sum = 0; count = 0; min = Long.MAX_VALUE; max = 0
    }

    @Synchronized
    fun summary(): RttSummary? =
        if (count == 0L) null else RttSummary(last, sum / count, min, max, count)
}

data class TrafficCounts(val envelopesIn: Long, val envelopesOut: Long, val bytesIn: Long, val bytesOut: Long)

private class Traffic {
    val envelopesIn = AtomicLong()
    val envelopesOut = AtomicLong()
    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()

    fun counts() = TrafficCounts(envelopesIn.get(), envelopesOut.get(), bytesIn.get(), bytesOut.get())

    fun reset() {
        envelopesIn.set(0); envelopesOut.set(0); bytesIn.set(0); bytesOut.set(0)
    }
}

data class ClipboardCounts(val sent: Long, val received: Long, val rejected: Long, val interrupted: Long)

data class FileCounts(val sent: Long, val received: Long, val failed: Long, val resumed: Long)

data class DeviceCounts(
    val statusSent: Long,
    val findsStarted: Long,
    val findsStopped: Long,
    val findsFailed: Long,
)

data class NotificationCounts(
    val forwarded: Long,
    val suppressedMuted: Long,
    val classifiedVoip: Long,
    val textTruncated: Long,
    val lastSnapshotRecords: Int,
    val lastSnapshotBytes: Int,
    val snapshotIconsDropped: Long,
    val snapshotRecordsDropped: Long,
)

/**
 * What is known about the endpoints: the relay (null fields for a local-only pairing), the connection
 * mode and the Mac's local address from the pairing. Fingerprints are kept shortened; secrets never enter here.
 */
data class Endpoint(
    val host: String?,
    val port: Int?,
    val relayFingerprint: String?,
    val macFingerprint: String,
    val mode: String = "relay",
    val local: String? = null,
)

data class DiagnosticsSnapshot(
    val takenAtMs: Long,
    val state: String,
    val stateSinceMs: Long,
    val endpoint: Endpoint?,
    /** "relay" or "local" while a session is up. */
    val sessionPath: String? = null,
    val negotiatedVersion: Int?,
    val capabilities: List<String>,
    val sessionStartedAtMs: Long?,
    val reconnects: Long,
    val reconnectReasons: Map<DisconnectReason, Long>,
    val events: List<DiagnosticEvent>,
    val pushTokenPresent: Boolean,
    val lastPushAtMs: Long?,
    val rtt: RttSummary?,
    val sessionTraffic: TrafficCounts,
    val totalTraffic: TrafficCounts,
    val framesRejected: Long,
    val inboundByFeature: Map<InboundFeature, Long>,
    val droppedByFeature: Map<InboundFeature, Long>,
    val reliable: ReliableStream.Stats?,
    val clipboard: ClipboardCounts,
    val notifications: NotificationCounts,
    /** Whether battery optimization is off for the app; null when the caller didn't check. */
    val batteryOptimizationIgnored: Boolean? = null,
    /** Whether "Display over other apps" is allowed; null when the caller didn't check. */
    val overlayAllowed: Boolean? = null,
    val files: FileCounts = FileCounts(0, 0, 0, 0),
    val device: DeviceCounts = DeviceCounts(0, 0, 0, 0),
)

/**
 * Live counters for the diagnostics screen and report. Every hook is a few atomic operations, so the
 * transport can call them on hot paths. Nothing here ever holds the room, PSK or push token.
 */
class DiagnosticsRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
    eventCapacity: Int = 20,
) {
    private val events = EventLog(eventCapacity)

    @Volatile private var state = "Not started"
    @Volatile private var stateSince = clock()
    @Volatile private var endpoint: Endpoint? = null
    @Volatile private var negotiatedVersion: Int? = null
    @Volatile private var sessionPath: String? = null
    @Volatile private var capabilities: List<String> = emptyList()
    @Volatile private var sessionStartedAt: Long? = null
    @Volatile private var pendingReason: DisconnectReason? = null
    @Volatile private var lastPushAt: Long? = null
    @Volatile private var lastSnapshotRecords = 0
    @Volatile private var lastSnapshotBytes = 0

    private val reconnects = AtomicLong()
    private val reasons = ConcurrentHashMap<DisconnectReason, AtomicLong>()
    private val rtt = RttStats()
    private val session = Traffic()
    private val total = Traffic()
    private val framesRejected = AtomicLong()
    private val inbound = ConcurrentHashMap<InboundFeature, AtomicLong>()

    private val clipboardSent = AtomicLong()
    private val clipboardReceived = AtomicLong()
    private val clipboardRejected = AtomicLong()
    private val clipboardInterrupted = AtomicLong()

    private val filesSent = AtomicLong()
    private val filesReceived = AtomicLong()
    private val filesFailed = AtomicLong()
    private val filesResumed = AtomicLong()

    private val statusSent = AtomicLong()
    private val findsStarted = AtomicLong()
    private val findsStopped = AtomicLong()
    private val findsFailed = AtomicLong()

    private val forwarded = AtomicLong()
    private val suppressedMuted = AtomicLong()
    private val classifiedVoip = AtomicLong()
    private val textTruncated = AtomicLong()
    private val snapshotIconsDropped = AtomicLong()
    private val snapshotRecordsDropped = AtomicLong()

    // Connection

    fun onState(label: String) {
        if (label == state) return
        state = label
        stateSince = clock()
        event(EventKind.STATE, label)
    }

    fun onEndpoint(
        host: String?,
        port: Int?,
        relayFingerprint: String?,
        macFingerprint: String,
        mode: String = "relay",
        local: String? = null,
    ) {
        endpoint = Endpoint(host, port, relayFingerprint?.let(::shortFingerprint), shortFingerprint(macFingerprint), mode, local)
    }

    /** A connection attempt is starting; counts a reconnect if a previous session or attempt ended. */
    fun onConnectAttempt() {
        val reason = pendingReason ?: return
        pendingReason = null
        reconnects.incrementAndGet()
        reasons.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }

    fun onSessionStart(version: Int, capabilities: Collection<String>, path: String? = null) {
        negotiatedVersion = version
        sessionPath = path
        this.capabilities = capabilities.sorted()
        sessionStartedAt = clock()
        session.reset()
        rtt.reset()
        val via = path?.let { " via $it" }.orEmpty()
        event(EventKind.INFO, "session v$version$via [${this.capabilities.joinToString(",")}]")
    }

    /** The session (or attempt) ended for [reason]; [detail] goes into the event log. */
    fun onSessionEnd(reason: DisconnectReason, detail: String? = null) {
        // Keep the first cause: closing the socket on a network change also surfaces as an IO error.
        if (pendingReason == null) pendingReason = reason
        sessionStartedAt = null
        negotiatedVersion = null
        sessionPath = null
        capabilities = emptyList()
        val kind = when (reason) {
            DisconnectReason.RELAY_WAIT, DisconnectReason.MAC_ASLEEP, DisconnectReason.MANUAL -> EventKind.INFO
            DisconnectReason.NETWORK_CHANGE -> EventKind.NETWORK
            else -> EventKind.ERROR
        }
        event(kind, if (detail.isNullOrBlank()) reason.label else "${reason.label}: $detail")
    }

    fun onNetworkChange(detail: String) = event(EventKind.NETWORK, detail)

    fun onInfo(detail: String) = event(EventKind.INFO, detail)

    /** Android refused to start the foreground service (background start limits). */
    fun onServiceStartBlocked(detail: String) = event(EventKind.ERROR, "service start blocked: $detail")

    fun onPush(detail: String) {
        lastPushAt = clock()
        event(EventKind.PUSH, detail)
    }

    fun onRtt(rttMs: Long) = rtt.record(rttMs)

    // Traffic

    fun onFrameIn(bytes: Int) {
        session.envelopesIn.incrementAndGet(); session.bytesIn.addAndGet(bytes.toLong())
        total.envelopesIn.incrementAndGet(); total.bytesIn.addAndGet(bytes.toLong())
    }

    fun onFrameOut(bytes: Int) {
        session.envelopesOut.incrementAndGet(); session.bytesOut.addAndGet(bytes.toLong())
        total.envelopesOut.incrementAndGet(); total.bytesOut.addAndGet(bytes.toLong())
    }

    fun onFrameRejected(detail: String) {
        framesRejected.incrementAndGet()
        event(EventKind.ERROR, "frame rejected: $detail")
    }

    fun onInbound(feature: InboundFeature) {
        inbound.getOrPut(feature) { AtomicLong() }.incrementAndGet()
    }

    // Clipboard

    fun onClipboardSent() = clipboardSent.incrementAndGet()
    fun onClipboardReceived() = clipboardReceived.incrementAndGet()
    fun onClipboardRejected() = clipboardRejected.incrementAndGet()
    fun onClipboardInterrupted() = clipboardInterrupted.incrementAndGet()

    // Files

    fun onFileSent() = filesSent.incrementAndGet()
    fun onFileReceived() = filesReceived.incrementAndGet()
    fun onFileResumed() = filesResumed.incrementAndGet()

    fun onFileFailed(detail: String) {
        filesFailed.incrementAndGet()
        event(EventKind.ERROR, "file transfer failed: $detail")
    }

    // Phone status and Find my phone

    fun onDeviceStatusSent() = statusSent.incrementAndGet()

    fun onFindStarted() {
        findsStarted.incrementAndGet()
        event(EventKind.INFO, "find my phone ringing")
    }

    fun onFindStopped(reason: String) {
        findsStopped.incrementAndGet()
        event(EventKind.INFO, "find my phone stopped: $reason")
    }

    fun onFindFailed(error: String) {
        findsFailed.incrementAndGet()
        event(EventKind.ERROR, "find my phone failed: $error")
    }

    // Notifications

    fun onNotificationForwarded() = forwarded.incrementAndGet()
    fun onNotificationMuted() = suppressedMuted.incrementAndGet()
    fun onNotificationVoip() = classifiedVoip.incrementAndGet()
    fun onTextTruncated() = textTruncated.incrementAndGet()

    fun onSnapshot(records: Int, bytes: Int, iconsDropped: Int, recordsDropped: Int) {
        lastSnapshotRecords = records
        lastSnapshotBytes = bytes
        snapshotIconsDropped.addAndGet(iconsDropped.toLong())
        snapshotRecordsDropped.addAndGet(recordsDropped.toLong())
        if (iconsDropped > 0 || recordsDropped > 0) {
            event(EventKind.INFO, "snapshot over budget: dropped $iconsDropped icons, $recordsDropped records")
        }
    }

    private fun event(kind: EventKind, message: String) = events.add(DiagnosticEvent(clock(), kind, message))

    /** A consistent-enough copy of everything; external sources are passed in by the caller. */
    fun snapshot(
        pushTokenPresent: Boolean,
        dropped: Map<InboundFeature, Long>,
        reliable: ReliableStream.Stats?,
        batteryOptimizationIgnored: Boolean? = null,
        overlayAllowed: Boolean? = null,
    ) = DiagnosticsSnapshot(
        takenAtMs = clock(),
        state = state,
        stateSinceMs = stateSince,
        endpoint = endpoint,
        sessionPath = sessionPath,
        negotiatedVersion = negotiatedVersion,
        capabilities = capabilities,
        sessionStartedAtMs = sessionStartedAt,
        reconnects = reconnects.get(),
        reconnectReasons = reasons.mapValues { it.value.get() },
        events = events.snapshot(),
        pushTokenPresent = pushTokenPresent,
        lastPushAtMs = lastPushAt,
        rtt = rtt.summary(),
        sessionTraffic = session.counts(),
        totalTraffic = total.counts(),
        framesRejected = framesRejected.get(),
        inboundByFeature = InboundFeature.entries.associateWith { inbound[it]?.get() ?: 0L },
        droppedByFeature = dropped,
        reliable = reliable,
        clipboard = ClipboardCounts(
            clipboardSent.get(), clipboardReceived.get(), clipboardRejected.get(), clipboardInterrupted.get(),
        ),
        notifications = NotificationCounts(
            forwarded = forwarded.get(),
            suppressedMuted = suppressedMuted.get(),
            classifiedVoip = classifiedVoip.get(),
            textTruncated = textTruncated.get(),
            lastSnapshotRecords = lastSnapshotRecords,
            lastSnapshotBytes = lastSnapshotBytes,
            snapshotIconsDropped = snapshotIconsDropped.get(),
            snapshotRecordsDropped = snapshotRecordsDropped.get(),
        ),
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        overlayAllowed = overlayAllowed,
        files = FileCounts(filesSent.get(), filesReceived.get(), filesFailed.get(), filesResumed.get()),
        device = DeviceCounts(statusSent.get(), findsStarted.get(), findsStopped.get(), findsFailed.get()),
    )

    companion object {
        fun shortFingerprint(fingerprint: String): String = fingerprint.take(8)
    }
}
