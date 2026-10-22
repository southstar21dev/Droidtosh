package dev.southstar21.droidtosh.transport

import kotlin.random.Random

class ReconnectPolicy(
    private val maxDelayMs: Long = 30_000,
    private val jitterFraction: () -> Double = { Random.nextDouble(-0.2, 0.2) },
) {
    fun delayMs(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 30)
        val base = minOf(1_000L shl exponent, maxDelayMs)
        val jittered = base + (base * jitterFraction()).toLong()
        return jittered.coerceAtLeast(250L)
    }
}
