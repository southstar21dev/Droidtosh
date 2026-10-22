package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RejoinGraceTest {
    private var now = 1_000_000L
    private val grace = RejoinGrace(clock = { now })

    @Test
    fun noGraceBeforeStart() {
        assertNull(grace.retryDelayMs(1_000L))
    }

    @Test
    fun freshStartRetriesWithBackoffThenFallsBackToTheLongWait() {
        grace.onStart()
        assertEquals(1_000L, grace.retryDelayMs(1_000L))
        now += 29_999L
        assertEquals(16_000L, grace.retryDelayMs(16_000L))
        now += 1L
        assertNull(grace.retryDelayMs(1_000L))
    }

    @Test
    fun afterASessionDropsRetriesQuicklyThenProbesThenWaits() {
        grace.onStart()
        now += 60_000L
        assertNull(grace.retryDelayMs(1_000L))
        grace.onSessionEnded(macAsleep = false)
        // The quick window: the backoff, capped so a returning Mac is found within seconds.
        assertEquals(1_000L, grace.retryDelayMs(1_000L))
        now += 30_000L
        assertEquals(RejoinGrace.QUICK_PROBE_MS, grace.retryDelayMs(16_000L))
        now += RejoinGrace.QUICK_WINDOW_MS - 30_000L - 1
        assertEquals(RejoinGrace.QUICK_PROBE_MS, grace.retryDelayMs(30_000L))
        // Then a gentle probe, whatever the backoff, until the probe window ends.
        now += 1L
        assertEquals(RejoinGrace.PROBE_MS, grace.retryDelayMs(1_000L))
        now += RejoinGrace.PROBE_WINDOW_MS - RejoinGrace.QUICK_WINDOW_MS - 1
        assertEquals(RejoinGrace.PROBE_MS, grace.retryDelayMs(30_000L))
        now += 1L
        assertNull(grace.retryDelayMs(1_000L))
    }

    @Test
    fun aNewSessionEndRestartsTheWindows() {
        grace.onSessionEnded(macAsleep = false)
        now += RejoinGrace.PROBE_WINDOW_MS
        assertNull(grace.retryDelayMs(1_000L))
        grace.onSessionEnded(macAsleep = false)
        assertEquals(1_000L, grace.retryDelayMs(1_000L))
    }

    @Test
    fun macAsleepEndsEveryWindow() {
        grace.onStart()
        now += 1_000L
        grace.onSessionEnded(macAsleep = true)
        assertNull(grace.retryDelayMs(1_000L))

        grace.onSessionEnded(macAsleep = false)
        now += 1_000L
        grace.onSessionEnded(macAsleep = true)
        assertNull(grace.retryDelayMs(1_000L))
    }

    @Test
    fun probeCostAfterADropStaysBounded() {
        // Every WAIT retried as the loop would (no jitter), until the long wait takes over.
        val backoff = ReconnectPolicy(jitterFraction = { 0.0 })
        grace.onSessionEnded(macAsleep = false)
        var attempt = 0
        var probes = 0
        while (true) {
            val delay = grace.retryDelayMs(backoff.delayMs(attempt)) ?: break
            now += delay
            attempt += 1
            probes += 1
        }
        // 26 quick probes in the first two minutes, then 16 more, one every 30 s, up to ten minutes.
        assertEquals(42, probes)
    }
}
