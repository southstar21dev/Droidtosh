package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun doublesUntilCapWithoutJitter() {
        val policy = ReconnectPolicy(jitterFraction = { 0.0 })
        assertEquals(1_000L, policy.delayMs(0))
        assertEquals(2_000L, policy.delayMs(1))
        assertEquals(4_000L, policy.delayMs(2))
        assertEquals(16_000L, policy.delayMs(4))
        assertEquals(30_000L, policy.delayMs(5))
        assertEquals(30_000L, policy.delayMs(20))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val policy = ReconnectPolicy(jitterFraction = { 0.2 })
        assertEquals(1_200L, policy.delayMs(0))
        val negative = ReconnectPolicy(jitterFraction = { -0.2 })
        assertEquals(800L, negative.delayMs(0))
    }

    @Test
    fun neverGoesBelowFloor() {
        val policy = ReconnectPolicy(jitterFraction = { -10.0 })
        assertTrue(policy.delayMs(0) >= 250L)
    }
}
