package dev.southstar21.droidtosh.data

import dev.southstar21.droidtosh.transport.ConnectionMode
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialStoreTest {
    private val psk = ByteArray(32) { 7 }
    private val creds = PairingCredentials("h", 7443, "room", "fp", "rfp", psk)

    private val encodedPsk = Base64.getEncoder().encodeToString(psk)

    @Test
    fun roundTripsV4Payload() {
        assertEquals(creds, CredentialStore.decode(CredentialStore.encode(creds)))
        val both = creds.copy(mode = ConnectionMode.BOTH, localHost = "192.168.1.5", localPort = 7444)
        assertEquals(both, CredentialStore.decode(CredentialStore.encode(both)))
    }

    @Test
    fun encodesV4Format() {
        assertEquals(
            "v4\nh\n7443\nroom\nfp\nrfp\n$encodedPsk\nrelay\n\n",
            CredentialStore.encode(creds),
        )
    }

    @Test
    fun roundTripsALocalOnlyPairingWithoutRelayFields() {
        val local = PairingCredentials(null, null, "room", "fp", null, psk, ConnectionMode.LOCAL, null, 7444)
        assertEquals("v4\n\n\nroom\nfp\n\n$encodedPsk\nlocal\n\n7444", CredentialStore.encode(local))
        val decoded = CredentialStore.decode(CredentialStore.encode(local))
        assertEquals(local, decoded)
        assertEquals(false, decoded?.hasRelay)
    }

    @Test
    fun v3PayloadIsARelayPairing() {
        val stored = CredentialStore.decode("v3\nh\n7443\nroom\nfp\nrfp\n$encodedPsk")
        assertEquals(creds, stored)
        assertEquals(ConnectionMode.RELAY, stored?.mode)
    }

    @Test
    fun relayModeWithoutRelayFieldsIsRejected() {
        assertNull(CredentialStore.decode("v4\n\n\nroom\nfp\n\n$encodedPsk\nboth\n\n7444"))
        assertNull(CredentialStore.decode("v4\nh\n7443\nroom\nfp\nrfp\n$encodedPsk\nsideways\n\n"))
    }

    @Test
    fun v2PayloadWithoutRelayFingerprintIsNotUsable() {
        val legacy = "v2\nh\n7443\nroom\nfp\n${Base64.getEncoder().encodeToString(psk)}"
        assertNull(CredentialStore.decode(legacy))
    }

    @Test
    fun v1PayloadIsNotUsable() {
        val legacy = "h\n7443\nfp\n${Base64.getEncoder().encodeToString(psk)}"
        assertNull(CredentialStore.decode(legacy))
    }

    @Test
    fun malformedV3PayloadIsRejected() {
        assertNull(CredentialStore.decode("v3\nh\nnot-a-port\nroom\nfp\nrfp\nAAAA"))
        assertNull(CredentialStore.decode("v3\nh\n7443\nroom\nfp\nrfp"))
        assertNull(CredentialStore.decode("v3\nh\n7443\nroom\nfp\nrfp\n!!!!"))
    }
}
