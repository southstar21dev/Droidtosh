package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitModePolicyTest {
    @Test
    fun fcmEnabledUsesSafetyNet() {
        assertEquals(15 * 60_000L, WaitModePolicy(fcmEnabled = true).nextDelayMs())
    }

    @Test
    fun fcmDisabledProbesEveryMinute() {
        assertEquals(60_000L, WaitModePolicy(fcmEnabled = false).nextDelayMs())
    }

    @Test
    fun pushAvailabilityIsReadOnEveryWait() {
        var push = false
        val policy = WaitModePolicy(pushAvailable = { push })
        assertEquals(WaitModePolicy.NO_FCM_PROBE_MS, policy.nextDelayMs())
        push = true
        assertEquals(WaitModePolicy.FCM_SAFETY_NET_MS, policy.nextDelayMs())
        assertEquals(WaitModePolicy.NO_FCM_PROBE_MS, policy.nextDelayMs(fcmUsable = false))
    }
}
