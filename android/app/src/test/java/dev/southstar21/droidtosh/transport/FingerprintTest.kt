package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {

    @Test
    fun sha256OfEmptyData() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Fingerprint.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun pinVerifierAcceptsMatchingFingerprint() {
        val der = byteArrayOf(1, 2, 3, 4)
        val expected = Fingerprint.sha256Hex(der)
        assertTrue(PinVerifier.matches(der, expected))
        assertTrue(PinVerifier.matches(der, expected.uppercase()))
    }

    @Test
    fun pinVerifierRejectsMismatch() {
        assertFalse(PinVerifier.matches(byteArrayOf(1, 2, 3, 4), "00".repeat(32)))
    }
}
