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
}
