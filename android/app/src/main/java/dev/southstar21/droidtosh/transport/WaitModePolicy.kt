package dev.southstar21.droidtosh.transport

class WaitModePolicy(
    private val fcmEnabled: Boolean,
    /** Overrides both delays; the end-to-end harness uses it to keep the Mac-asleep wait short. */
    private val overrideMs: Long? = null,
) {
    /** [fcmUsable] is false when no relay is in use (local-only mode), so no `mac_online` push can come. */
    fun nextDelayMs(fcmUsable: Boolean = true): Long =
        overrideMs ?: if (fcmEnabled && fcmUsable) FCM_SAFETY_NET_MS else NO_FCM_PROBE_MS

    companion object {
        const val FCM_SAFETY_NET_MS = 15 * 60_000L
        const val NO_FCM_PROBE_MS = 60_000L
    }
}

/**
 * Decides how soon a relay WAIT (no Mac parked) is retried, or whether it means "the Mac is away"
 * and the loop should enter the long [WaitModePolicy] wait, which relies on the relay's FCM
 * `mac_online` push to end early.
 *
 * - After a session ends for any reason other than the Mac announcing sleep (the Mac app quit,
 *   crashed or was updated, or a network drop), the Mac usually comes back soon, and FCM can't be
 *   relied on to say so (it may be missing, delayed by Doze or dropped by a battery manager). So for
 *   [quickWindowMs] a WAIT is retried with the normal backoff capped at [quickProbeMs], then every
 *   [probeMs] until [probeWindowMs] after the session ended.
 * - A freshly started loop may beat the Mac to the relay: for [startGraceMs] after [onStart] a WAIT
 *   is retried with the normal backoff.
 * - After the Mac said it is going to sleep there is no grace: the first WAIT enters the long wait,
 *   so a sleeping Mac stays cheap.
 */
class RejoinGrace(
    private val clock: () -> Long,
    private val startGraceMs: Long = START_GRACE_MS,
    private val quickWindowMs: Long = QUICK_WINDOW_MS,
    private val quickProbeMs: Long = QUICK_PROBE_MS,
    private val probeWindowMs: Long = PROBE_WINDOW_MS,
    private val probeMs: Long = PROBE_MS,
) {
    private var startedAt: Long? = null
    private var sessionEndedAt: Long? = null

    /** The loop (re)started: a fresh process or service start. */
    fun onStart() {
        startedAt = clock()
    }

    /** An authenticated session ended; [macAsleep] when the Mac said it was going to sleep. */
    fun onSessionEnded(macAsleep: Boolean) {
        startedAt = null
        sessionEndedAt = if (macAsleep) null else clock()
    }

    /**
     * How long to wait before retrying a WAIT, given the loop's normal [backoffMs] for this attempt;
     * null when the loop should wait for the Mac instead.
     */
    fun retryDelayMs(backoffMs: Long): Long? {
        val now = clock()
        sessionEndedAt?.let { endedAt ->
            val elapsed = now - endedAt
            if (elapsed < quickWindowMs) return minOf(backoffMs, quickProbeMs)
            if (elapsed < probeWindowMs) return probeMs
        }
        startedAt?.let { if (now - it < startGraceMs) return backoffMs }
        return null
    }

    companion object {
        const val START_GRACE_MS = 30_000L
        const val QUICK_WINDOW_MS = 2 * 60_000L
        const val QUICK_PROBE_MS = 5_000L
        const val PROBE_WINDOW_MS = 10 * 60_000L
        const val PROBE_MS = 30_000L
    }
}
