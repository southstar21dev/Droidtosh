package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindAction
import dev.southstar21.droidtosh.protocol.v1.FindEndReason
import dev.southstar21.droidtosh.protocol.v1.FindPhoneState
import dev.southstar21.droidtosh.transport.newEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The ring itself: sound, vibration and the ringing UI. */
interface Alarm {
    /** Starts ringing; false if the sound couldn't start (nothing is left running then). */
    fun start(): Boolean
    fun stop()
}

/**
 * Find my phone (protocol/PROTOCOL-v2.md §9). Pure Kotlin: the transport, alarm, setting and clock
 * are injected. Feed it requests with [onEnvelope]; the Stop button, a dismissed notification or a
 * key on the ringing screen call [stopOnPhone], a sound failure mid-ring calls [onAlarmFailed], and
 * [tick] (at [nextWakeInMs]) ends the ring after [ringMs].
 *
 * One instance per service start: each request id is handled once for the instance's lifetime.
 * Thread-safe; all public calls are serialized on the instance.
 */
class FindPhoneController(
    private val send: (Envelope) -> Boolean,
    private val alarm: Alarm,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ringMs: Long = RING_MS,
    private val staleMs: Long = STALE_MS,
    private val maxRemembered: Int = MAX_REMEMBERED,
) {
    // Keyed by action too, so a STOP that reuses its START's id still stops the ring.
    private val handled = LinkedHashSet<String>()
    private var ringingId: String? = null
    private var ringUntil = 0L

    private val mutableRinging = MutableStateFlow(false)

    /** True while the phone rings; the ringing screen closes itself when it turns false. */
    val ringing: StateFlow<Boolean> = mutableRinging.asStateFlow()

    @Synchronized
    fun onEnvelope(envelope: Envelope) {
        if (!envelope.hasFindPhoneRequest()) return
        val request = envelope.findPhoneRequest
        val action = request.action
        if (action != FindAction.FIND_ACTION_START && action != FindAction.FIND_ACTION_STOP) return
        if (!remember("${action.number}:${request.requestId}")) return
        when (action) {
            FindAction.FIND_ACTION_START -> start(request.requestId, envelope.sentAtMs)
            else -> end(FindEndReason.FIND_END_STOPPED_FROM_MAC)
        }
    }

    /** Stop button, notification dismissed, or a key pressed on the ringing screen. */
    @Synchronized
    fun stopOnPhone() = end(FindEndReason.FIND_END_STOPPED_ON_PHONE)

    /** The sound stopped on its own (player error) while ringing. */
    @Synchronized
    fun onAlarmFailed() {
        val id = ringingId ?: return
        stopAlarm()
        report(id, ringing = false, FindEndReason.FIND_END_FAILED, ERROR_AUDIO_FAILED)
    }

    /** Ends the ring once it has gone on for [ringMs]. */
    @Synchronized
    fun tick() {
        if (ringingId != null && clock() >= ringUntil) end(FindEndReason.FIND_END_TIMEOUT)
    }

    @Synchronized
    fun nextWakeInMs(): Long? = if (ringingId == null) null else (ringUntil - clock()).coerceAtLeast(0)

    /** The service is stopping: silence the phone. The Mac learns nothing more from this instance. */
    @Synchronized
    fun shutdown() {
        if (ringingId != null) stopAlarm()
    }

    private fun start(requestId: String, sentAtMs: Long) {
        val now = clock()
        // A sent_at of 0 means the sender didn't stamp it; don't treat that as ancient.
        if (sentAtMs > 0 && now - sentAtMs > staleMs) {
            report(requestId, ringing = false, FindEndReason.FIND_END_FAILED, ERROR_STALE)
            return
        }
        if (!enabled()) {
            report(requestId, ringing = false, FindEndReason.FIND_END_FAILED, ERROR_DISABLED)
            return
        }
        if (ringingId == null) {
            val started = runCatching { alarm.start() }.getOrDefault(false)
            if (!started) {
                report(requestId, ringing = false, FindEndReason.FIND_END_FAILED, ERROR_AUDIO_FAILED)
                return
            }
            mutableRinging.value = true
        }
        // Already ringing: restart the timer and adopt the new request.
        ringingId = requestId
        ringUntil = now + ringMs
        report(requestId, ringing = true, FindEndReason.FIND_END_UNSPECIFIED, "")
    }

    private fun end(reason: FindEndReason) {
        val id = ringingId ?: return
        stopAlarm()
        report(id, ringing = false, reason, "")
    }

    private fun stopAlarm() {
        ringingId = null
        mutableRinging.value = false
        runCatching { alarm.stop() }
    }

    private fun report(requestId: String, ringing: Boolean, reason: FindEndReason, error: String) {
        send(newEnvelope {
            setFindPhoneState(
                FindPhoneState.newBuilder()
                    .setRequestId(requestId)
                    .setRinging(ringing)
                    .setEnded(reason)
                    .setError(error)
            )
        })
    }

    /** Records [key]; false if it was already handled. */
    private fun remember(key: String): Boolean {
        if (!handled.add(key)) return false
        if (handled.size > maxRemembered) handled.remove(handled.first())
        return true
    }

    companion object {
        const val RING_MS = 60_000L
        const val STALE_MS = 90_000L
        const val MAX_REMEMBERED = 512
        const val ERROR_DISABLED = "disabled"
        const val ERROR_AUDIO_FAILED = "audio_failed"
        const val ERROR_STALE = "stale"
    }
}
