package dev.southstar21.droidtosh.transport

import java.net.URLEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadParserTest {
    private val psk = ByteArray(32) { 0x5A }
    private val room = "A".repeat(43)
    private val fp = "a".repeat(64)
    private val rfp = "b".repeat(64)

    private fun uri(
        name: String = "Work Mac",
        version: Int = 2,
        key: ByteArray = psk,
        room: String = this.room,
        relayFingerprint: String? = rfp,
        fingerprint: String = fp,
    ): String {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        return "droidsync://pair?v=$version&did=mac-1&name=$encodedName" +
            "&host=192.168.1.20&port=8765&fp=$fingerprint" +
            (relayFingerprint?.let { "&rfp=$it" } ?: "") +
            "&room=$room&psk=${Base64URL.encode(key)}"
    }

    @Test
    fun parsesValidPayload() {
        val parsed = PairingPayloadParser.parse(uri())
        assertEquals("mac-1", parsed.deviceId)
        assertEquals("Work Mac", parsed.deviceName)
        assertEquals("192.168.1.20", parsed.host)
        assertEquals(8765, parsed.port)
        assertEquals(room, parsed.room)
        assertEquals(fp, parsed.fingerprint)
        assertEquals(rfp, parsed.relayFingerprint)
        assertArrayEquals(psk, parsed.psk)
    }

    @Test
    fun normalizesFingerprintsToLowercase() {
        val parsed = PairingPayloadParser.parse(uri(relayFingerprint = "B".repeat(64)))
        assertEquals(rfp, parsed.relayFingerprint)
    }

    @Test
    fun rejectsMissingOrInvalidRelayFingerprint() {
        for (bad in listOf(null, "", "b".repeat(63), "g".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                PairingPayloadParser.parse(uri(relayFingerprint = bad))
            }
        }
    }

    @Test
    fun rejectsInvalidMacFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri(fingerprint = "zz"))
        }
    }

    @Test
    fun rejectsMissingRoom() {
        val withoutRoom = "droidsync://pair?v=2&did=mac-1&name=Mac" +
            "&host=192.168.1.20&port=8765&fp=$fp&rfp=$rfp" +
            "&psk=${Base64URL.encode(psk)}"
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(withoutRoom)
        }
    }

    @Test
    fun rejectsInvalidRoom() {
        val invalidRooms = listOf(
            "A".repeat(42),
            "A".repeat(44),
            "A".repeat(42) + "!",
            "A".repeat(42) + "=",
            "",
        )
        for (invalid in invalidRooms) {
            assertThrows(IllegalArgumentException::class.java) {
                PairingPayloadParser.parse(uri(room = invalid))
            }
        }
    }

    @Test
    fun rejectsForeignScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse("https://example.com")
        }
    }

    @Test
    fun rejectsOlderVersionWithRepairMessage() {
        val thrown = assertThrows(PairingVersionException::class.java) {
            PairingPayloadParser.parse(uri(version = 1, relayFingerprint = null))
        }
        assertEquals("Pairing code is from an older version — re-pair", thrown.message)
    }

    @Test
    fun rejectsNewerVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri(version = 3))
        }
    }

    @Test
    fun rejectsWrongPSKLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri(key = ByteArray(16)))
        }
    }

    @Test
    fun aCodeWithoutModeIsARelayPairing() {
        val parsed = PairingPayloadParser.parse(uri())
        assertEquals(ConnectionMode.RELAY, parsed.mode)
        assertNull(parsed.localHost)
        assertNull(parsed.localPort)
    }

    @Test
    fun parsesLocalOnlyCodeWithoutRelayFields() {
        val parsed = PairingPayloadParser.parse(
            "droidsync://pair?v=2&did=mac-1&name=Mac&fp=$fp&room=$room&psk=${Base64URL.encode(psk)}" +
                "&mode=local&lhost=192.168.1.5&lport=7444",
        )
        assertEquals(ConnectionMode.LOCAL, parsed.mode)
        assertNull(parsed.host)
        assertNull(parsed.port)
        assertNull(parsed.relayFingerprint)
        assertEquals("192.168.1.5", parsed.localHost)
        assertEquals(7444, parsed.localPort)
    }

    @Test
    fun parsesBothModeWithOptionalLocalHost() {
        val parsed = PairingPayloadParser.parse(uri() + "&mode=both&lport=7444")
        assertEquals(ConnectionMode.BOTH, parsed.mode)
        assertEquals("192.168.1.20", parsed.host)
        assertNull(parsed.localHost)
        assertEquals(7444, parsed.localPort)
    }

    @Test
    fun localModesRequireALocalPort() {
        for (mode in listOf("local", "both")) {
            assertThrows(IllegalArgumentException::class.java) {
                PairingPayloadParser.parse(uri() + "&mode=$mode")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri() + "&mode=both&lport=70000")
        }
    }

    @Test
    fun relayModesStillRequireTheRelay() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri(relayFingerprint = null) + "&mode=both&lport=7444")
        }
    }

    @Test
    fun rejectsUnknownMode() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayloadParser.parse(uri() + "&mode=carrier-pigeon")
        }
    }

    @Test
    fun base64URLRoundTrip() {
        val data = ByteArray(64) { it.toByte() }
        assertArrayEquals(data, Base64URL.decode(Base64URL.encode(data)))
    }
}
