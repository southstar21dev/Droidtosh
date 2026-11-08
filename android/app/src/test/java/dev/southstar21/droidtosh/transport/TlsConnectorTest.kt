package dev.southstar21.droidtosh.transport

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsConnectorTest {

    @Test
    fun readsCompleteLine() {
        val input = ByteArrayInputStream("PAIRED\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals("PAIRED", readRelayResponseLine(input, 512))
    }

    @Test
    fun readsEmptyLine() {
        val input = ByteArrayInputStream("\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals("", readRelayResponseLine(input, 512))
    }

    @Test
    fun decodesUtf8Line() {
        val input = ByteArrayInputStream("ERROR résumé\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals("ERROR résumé", readRelayResponseLine(input, 512))
    }

    @Test
    fun stopsAtNewlineAndLeavesRemainingBytes() {
        val payload = byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01)
        val input = ByteArrayInputStream("WAIT\n".toByteArray(StandardCharsets.UTF_8) + payload)
        assertEquals("WAIT", readRelayResponseLine(input, 512))
        assertSequenceEquals(payload, input.readBytes())
    }

    @Test
    fun acceptsLineAtMaxLength() {
        val line = "x".repeat(512)
        val input = ByteArrayInputStream("$line\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(line, readRelayResponseLine(input, 512))
    }

    @Test
    fun rejectsLineOverMaxLength() {
        val line = "x".repeat(513)
        val input = ByteArrayInputStream("$line\n".toByteArray(StandardCharsets.UTF_8))
        assertThrows(IOException::class.java) {
            readRelayResponseLine(input, 512)
        }
    }

    @Test
    fun rejectsTruncatedLineWithoutNewline() {
        val input = ByteArrayInputStream("WAIT".toByteArray(StandardCharsets.UTF_8))
        assertThrows(IOException::class.java) {
            readRelayResponseLine(input, 512)
        }
    }

    @Test
    fun onlyARejectedCertificateAsksToRepair() {
        val pinned = SSLHandshakeException("bad certificate").apply {
            initCause(CertificateException("certificate fingerprint mismatch"))
        }
        assertTrue(relayHandshakeFailure(pinned).message!!.contains("Re-pair"))

        val cutShort = SSLHandshakeException("Remote host terminated the handshake").apply {
            initCause(EOFException("SSL peer shut down incorrectly"))
        }
        assertFalse(relayHandshakeFailure(cutShort).message!!.contains("Re-pair"))
    }

    private fun assertSequenceEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
