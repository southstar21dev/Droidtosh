package dev.southstar21.droidtosh.transport

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {

    @Test
    fun hmacMatchesRfc4231TestCase1() {
        val expectedHex = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        val key = ByteArray(20) { 0x0b }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val actual = mac.doFinal("Hi There".toByteArray(Charsets.US_ASCII))
        assertEquals(expectedHex, actual.joinToString("") { "%02x".format(it) })

        // RFC 4231 Test Case 1 applied to our own mac() construction:
        // psk = 0x0b x20, role = "", serverNonce = empty, clientNonce = "Hi There".
        assertArrayEquals(
            hexToBytes(expectedHex),
            Auth.mac(ByteArray(20) { 0x0b }, "", ByteArray(0), "Hi There".toByteArray(Charsets.US_ASCII))
        )
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun macInputLayout() {
        val input = Auth.macInput("client", byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertArrayEquals("client".toByteArray(Charsets.UTF_8) + byteArrayOf(1, 2, 3, 4), input)
    }

    @Test
    fun roleSeparation() {
        val psk = ByteArray(32) { 0x11 }
        val server = ByteArray(32) { 0x22 }
        val client = ByteArray(32) { 0x33 }
        val clientMac = Auth.mac(psk, "client", server, client)
        assertEquals(32, clientMac.size)
        assertArrayEquals(clientMac, Auth.mac(psk, "client", server, client))
        assertFalse(clientMac.contentEquals(Auth.mac(psk, "server", server, client)))
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(Auth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Auth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Auth.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
