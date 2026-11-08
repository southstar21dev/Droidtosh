package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.ActionResult
import dev.southstar21.droidtosh.protocol.v1.AuthChallenge
import dev.southstar21.droidtosh.protocol.v1.AuthOk
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Ping
import dev.southstar21.droidtosh.protocol.v1.ProtocolError
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolClientTest {
    private val psk = ByteArray(32) { 0x42 }
    private val serverNonce = ByteArray(32) { 0x01 }
    private val clientNonce = ByteArray(32) { 0x02 }

    private fun challenge() = newEnvelope {
        setAuthChallenge(
            AuthChallenge.newBuilder()
                .setServerNonce(ByteString.copyFrom(serverNonce))
                .setMinVersion(2)
                .setMaxVersion(2)
        )
    }

    private fun authOk() = newEnvelope {
        setAuthOk(
            AuthOk.newBuilder()
                .setMac(ByteString.copyFrom(Auth.mac(psk, "server", serverNonce, clientNonce)))
                .setVersion(2)
                .addCapabilities("reliable")
                .addCapabilities("clipboard.text")
        )
    }

    @Test
    fun handshakeCompletesAndSendsClientMac() {
        val stream = FakeDuplexStream(listOf(challenge().toByteArray(), authOk().toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        client.performHandshake()

        assertEquals(1, stream.written.size)
        val response = Envelope.parseFrom(stream.written[0])
        assertArrayEquals(
            Auth.mac(psk, "client", serverNonce, clientNonce),
            response.authResponse.mac.toByteArray()
        )
        assertEquals(2, client.negotiatedVersion)
        assertEquals(setOf("reliable", "clipboard.text"), client.capabilities)
    }

    @Test
    fun handshakeReportsUnsupportedVersionError() {
        val error = newEnvelope {
            setError(ProtocolError.newBuilder().setCode("unsupported_version"))
        }
        val stream = FakeDuplexStream(listOf(challenge().toByteArray(), error.toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        val thrown = assertThrows(IllegalStateException::class.java) { client.performHandshake() }
        assertEquals("Mac and phone versions don't match; update Droidtosh on both", thrown.message)
    }

    @Test
    fun handshakeRejectsBadServerMac() {
        val badAuthOk = newEnvelope {
            setAuthOk(AuthOk.newBuilder().setMac(ByteString.copyFrom(ByteArray(32))).setVersion(2))
        }
        val stream = FakeDuplexStream(listOf(challenge().toByteArray(), badAuthOk.toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        assertThrows(AuthError.InvalidMAC::class.java) { client.performHandshake() }
    }

    @Test
    fun receiveParsesEnvelope() {
        val ping = newEnvelope { setPing(Ping.newBuilder().setSentAtMs(123)) }
        val stream = FakeDuplexStream(listOf(ping.toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        assertEquals(123L, client.receive().ping.sentAtMs)
    }

    @Test
    fun receiveRejectsMismatchedProtocolVersion() {
        val wrongVersion = Envelope.newBuilder().setProtocolVersion(1).build()
        val stream = FakeDuplexStream(listOf(wrongVersion.toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        val thrown = assertThrows(ProtocolVersionException::class.java) { client.receive() }

        assertEquals(1, thrown.version)
    }

    @Test
    fun handshakeRejectsV1Mac() {
        val wrongVersion = Envelope.newBuilder().setProtocolVersion(1).build()
        val stream = FakeDuplexStream(listOf(wrongVersion.toByteArray()))
        val client = ProtocolClient(stream, psk) { clientNonce }

        assertThrows(ProtocolVersionException::class.java) { client.performHandshake() }
    }

    @Test
    fun sendStampsSeqAndAckWhenReliable() {
        val stream = FakeDuplexStream()
        val client = ProtocolClient(stream, psk) { clientNonce }
        client.reliable = ReliableStream(clock = { 0L })

        client.send(newEnvelope { setActionResult(ActionResult.newBuilder().setRequestId("r")) })
        client.send(newEnvelope { setPing(Ping.getDefaultInstance()) })

        assertEquals(1L, Envelope.parseFrom(stream.written[0]).seq)
        assertEquals(0L, Envelope.parseFrom(stream.written[1]).seq)
    }
}
