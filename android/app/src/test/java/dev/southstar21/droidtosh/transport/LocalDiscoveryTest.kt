package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiscoveryTest {
    private val paired = "0123456789abcdef" + "a".repeat(48)

    @Test
    fun keepsOnlyServicesAdvertisingAPrefixOfThePairedFingerprint() {
        assertTrue(LocalDiscovery.matchesFingerprint("0123456789abcdef", paired))
        assertTrue(LocalDiscovery.matchesFingerprint("0123456789ABCDEF".lowercase(), paired.uppercase()))
        assertFalse(LocalDiscovery.matchesFingerprint("fedcba9876543210", paired))
        assertFalse(LocalDiscovery.matchesFingerprint(null, paired))
        // Too short to tell Macs apart.
        assertFalse(LocalDiscovery.matchesFingerprint("0123", paired))
    }
}
