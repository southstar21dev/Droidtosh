package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.AuthChallenge
import dev.southstar21.droidtosh.protocol.v1.AuthOk
import dev.southstar21.droidtosh.protocol.v1.Envelope
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientHandshakeTest {
    private val psk = ByteArray(32) { 0x42 }
    private fun serverNonce() = ByteArray(32) { 0x01 }
    private fun clientNonce() = ByteArray(32) { 0x02 }

    private fun challenge(min: Int = 2, max: Int = 2): Envelope = newEnvelope {
        setAuthChallenge(
            AuthChallenge.newBuilder()
                .setServerNonce(ByteString.copyFrom(serverNonce()))
                .setMinVersion(min)
                .setMaxVersion(max)
                .addAllCapabilities(Capabilities.SUPPORTED)
        )
    }

    private fun authOk(version: Int = 2, capabilities: List<String> = Capabilities.SUPPORTED) = newEnvelope {
        setAuthOk(
            AuthOk.newBuilder()
                .setMac(ByteString.copyFrom(Auth.mac(psk, "server", serverNonce(), clientNonce())))
                .setVersion(version)
                .addAllCapabilities(capabilities)
        )
    }

    @Test
    fun happyPath() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        val response = handshake.handleChallenge(challenge())
        assertArrayEquals(
            Auth.mac(psk, "client", serverNonce(), clientNonce()),
            response.authResponse.mac.toByteArray()
        )
        assertEquals(2, response.authResponse.minVersion)
        assertEquals(2, response.authResponse.maxVersion)
        assertEquals(Capabilities.SUPPORTED, response.authResponse.capabilitiesList)
        handshake.handleAuthOk(authOk(capabilities = listOf("notifications", "reliable")))
        assertEquals(
            ClientHandshake.State.Authenticated(2, setOf("notifications", "reliable")),
            handshake.state,
        )
    }

    @Test
    fun ignoresCapabilitiesThePhoneNeverOffered() {
        val handshake = ClientHandshake(psk, { clientNonce() }, listOf("notifications"))
        handshake.handleChallenge(challenge())
        handshake.handleAuthOk(authOk(capabilities = listOf("notifications", "files")))
        assertEquals(ClientHandshake.State.Authenticated(2, setOf("notifications")), handshake.state)
    }

    @Test
    fun challengeWithoutOverlappingVersionFails() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        assertThrows(ProtocolVersionException::class.java) {
            handshake.handleChallenge(challenge(min = 3, max = 4))
        }
        assertEquals(ClientHandshake.State.Failed, handshake.state)
    }

    @Test
    fun v1ChallengeWithoutVersionFieldsFails() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        assertThrows(ProtocolVersionException::class.java) {
            handshake.handleChallenge(challenge(min = 0, max = 0))
        }
    }

    @Test
    fun authOkWithUnsupportedVersionFails() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        handshake.handleChallenge(challenge())
        assertThrows(ProtocolVersionException::class.java) {
            handshake.handleAuthOk(authOk(version = 3))
        }
        assertEquals(ClientHandshake.State.Failed, handshake.state)
    }

    @Test
    fun wrongServerMacFails() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        handshake.handleChallenge(challenge())
        assertThrows(AuthError.InvalidMAC::class.java) {
            handshake.handleAuthOk(newEnvelope {
                setAuthOk(AuthOk.newBuilder().setMac(ByteString.copyFrom(ByteArray(32))).setVersion(2))
            })
        }
        assertEquals(ClientHandshake.State.Failed, handshake.state)
    }

    @Test
    fun outOfOrderFails() {
        val handshake = ClientHandshake(psk, { clientNonce() })
        assertThrows(AuthError.InvalidMessage::class.java) {
            handshake.handleAuthOk(newEnvelope { setAuthOk(AuthOk.getDefaultInstance()) })
        }
    }
}
