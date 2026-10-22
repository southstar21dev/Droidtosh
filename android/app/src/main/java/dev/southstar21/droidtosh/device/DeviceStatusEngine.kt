package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.protocol.v1.ChargeSource
import dev.southstar21.droidtosh.protocol.v1.DeviceStatus
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import dev.southstar21.droidtosh.transport.Capabilities
import dev.southstar21.droidtosh.transport.newEnvelope

/** What the Mac shows about the phone (protocol/PROTOCOL-v2.md §8). Defaults are the "unknown" values. */
data class PhoneStatusSnapshot(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val chargeSource: ChargeSource = ChargeSource.CHARGE_SOURCE_UNSPECIFIED,
    val powerSave: Boolean = false,
    val networkType: NetworkType = NetworkType.NETWORK_TYPE_UNSPECIFIED,
    val wifiLevel: Int = -1,
    val cellularLevel: Int = -1,
    val cellularGeneration: String = "",
    val carrier: String = "",
    val airplaneMode: Boolean = false,
    val ringerMode: RingerMode = RingerMode.RINGER_MODE_UNSPECIFIED,
    val doNotDisturb: Boolean = false,
) {
    /** Whether a change from [other] must reach the Mac within a second instead of being coalesced. */
    fun differsInFastFields(other: PhoneStatusSnapshot): Boolean =
        charging != other.charging ||
            networkType != other.networkType ||
            airplaneMode != other.airplaneMode ||
            ringerMode != other.ringerMode ||
            doNotDisturb != other.doNotDisturb

    fun toProto(sampledAtMs: Long): DeviceStatus = DeviceStatus.newBuilder()
        .setBatteryPercent(batteryPercent)
        .setCharging(charging)
        .setChargeSource(chargeSource)
        .setPowerSave(powerSave)
        .setNetworkType(networkType)
        .setWifiLevel(wifiLevel)
        .setCellularLevel(cellularLevel)
        .setCellularGeneration(cellularGeneration)
        .setCarrier(carrier)
        .setAirplaneMode(airplaneMode)
        .setRingerMode(ringerMode)
        .setDoNotDisturb(doNotDisturb)
        .setSampledAtMs(sampledAtMs)
        .build()
}

/** The phone's current status; [snapshot] is cheap (cached values kept up to date by callbacks). */
interface DeviceStatusSource {
    fun snapshot(): PhoneStatusSnapshot
}

/**
 * When to send [DeviceStatus] (protocol/PROTOCOL-v2.md §8.1). Pure Kotlin: the source, transport and
 * clock are injected. Callers report sessions with [onSessionStarted]/[onSessionEnded], source
 * changes with [onSourceChanged], call [tick] to send whatever is due, and sleep up to
 * [nextWakeInMs] (or until the next change) in between, so nothing polls while the phone is idle.
 *
 * - Once per session, as soon as it is up (after the Resume exchange).
 * - On change, at most one envelope per [coalesceMs]; a change of charging, network type, airplane
 *   mode, ringer or DND goes out after [fastDelayMs] instead (a short settle merges bursts).
 * - A heartbeat at least every [heartbeatMs] while connected.
 *
 * Thread-safe; all public calls are serialized on the instance.
 */
class DeviceStatusEngine(
    private val source: DeviceStatusSource,
    private val send: (Envelope) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val coalesceMs: Long = COALESCE_MS,
    private val fastDelayMs: Long = FAST_DELAY_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val retryMs: Long = RETRY_MS,
) {
    private var active = false
    private var initialPending = false
    private var lastSent: PhoneStatusSnapshot? = null
    private var lastSentAt = 0L
    // When an unsent change (of any field / of a fast field) was first seen.
    private var changedAt: Long? = null
    private var fastChangedAt: Long? = null
    private var retryAt: Long? = null

    /** A session is up with [capabilities]; only one that negotiated `phone.status` gets status. */
    @Synchronized
    fun onSessionStarted(capabilities: Set<String>) {
        clearPending()
        lastSent = null
        active = Capabilities.PHONE_STATUS in capabilities
        initialPending = active
    }

    @Synchronized
    fun onSessionEnded() {
        active = false
        initialPending = false
        lastSent = null
        clearPending()
    }

    /** The source may have changed; re-reads it and schedules a send if a shown field differs. */
    @Synchronized
    fun onSourceChanged() {
        if (!active) return
        val sent = lastSent ?: return
        val current = source.snapshot()
        if (current == sent) {
            changedAt = null
            fastChangedAt = null
            return
        }
        val now = clock()
        if (changedAt == null) changedAt = now
        if (fastChangedAt == null && current.differsInFastFields(sent)) fastChangedAt = now
    }

    /** Sends the status if anything is due. Returns true if it sent. */
    @Synchronized
    fun tick(): Boolean {
        if (!active) return false
        val now = clock()
        val due = dueAt() ?: return false
        if (now < due) return false
        val current = source.snapshot()
        val sent = lastSent
        val heartbeatDue = sent != null && now >= lastSentAt + heartbeatMs
        if (!initialPending && !heartbeatDue && current == sent) {
            // The change reverted before it went out.
            clearPending()
            return false
        }
        if (!send(newEnvelope { setDeviceStatus(current.toProto(now)) })) {
            retryAt = now + retryMs
            return false
        }
        initialPending = false
        lastSent = current
        lastSentAt = now
        clearPending()
        return true
    }

    /** How long until [tick] has something to do, or null when nothing is scheduled. */
    @Synchronized
    fun nextWakeInMs(): Long? {
        val due = dueAt() ?: return null
        return (due - clock()).coerceAtLeast(0)
    }

    private fun dueAt(): Long? {
        if (!active) return null
        retryAt?.let { return it }
        if (initialPending) return clock()
        val candidates = mutableListOf(lastSentAt + heartbeatMs)
        changedAt?.let { candidates += maxOf(it, lastSentAt + coalesceMs) }
        fastChangedAt?.let { candidates += it + fastDelayMs }
        return candidates.min()
    }

    private fun clearPending() {
        changedAt = null
        fastChangedAt = null
        retryAt = null
    }

    companion object {
        const val COALESCE_MS = 10_000L
        const val FAST_DELAY_MS = 500L
        const val HEARTBEAT_MS = 5 * 60_000L
        const val RETRY_MS = 1_000L
    }
}
