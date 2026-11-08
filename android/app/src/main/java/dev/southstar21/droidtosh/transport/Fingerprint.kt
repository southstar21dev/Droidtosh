package dev.southstar21.droidtosh.transport

import java.security.MessageDigest

object Fingerprint {
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

object PinVerifier {
    fun matches(certificateDER: ByteArray, expectedFingerprint: String): Boolean =
        Fingerprint.sha256Hex(certificateDER).equals(expectedFingerprint, ignoreCase = true)
}
