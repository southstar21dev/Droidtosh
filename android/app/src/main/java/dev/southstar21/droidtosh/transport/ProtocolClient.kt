package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.Envelope
import com.google.protobuf.InvalidProtocolBufferException
import java.io.EOFException

class ProtocolVersionException(val version: Int) :
    Exception("Mac uses protocol version $version; update Droidtosh on both devices")

class ProtocolClient(
    private val stream: DuplexStream,
    private val psk: ByteArray,
    capabilities: List<String> = Capabilities.SUPPORTED,
    private val nonceGenerator: () -> ByteArray = { randomNonce() },
) {
    private val handshake = ClientHandshake(psk, nonceGenerator, capabilities)

    /** Set after the handshake when `reliable` was negotiated; stamps seq/ack on everything sent. */
    @Volatile
    var reliable: ReliableStream? = null

    val negotiatedVersion: Int
        get() = (handshake.state as? ClientHandshake.State.Authenticated)?.version ?: PROTOCOL_VERSION

    val capabilities: Set<String>
        get() = (handshake.state as? ClientHandshake.State.Authenticated)?.capabilities ?: emptySet()

    fun performHandshake() {
        val challenge = receive()
        if (challenge.hasError()) throw handshakeError(challenge)
        val response = handshake.handleChallenge(challenge)
        send(response)
        val authOk = receive()
        if (authOk.hasError()) throw handshakeError(authOk)
        handshake.handleAuthOk(authOk)
    }

    private fun handshakeError(envelope: Envelope): Exception {
        val error = envelope.error
        return IllegalStateException(
            when (error.code) {
                "invalid_psk" -> "Pairing expired; re-pair with the Mac"
                "unsupported_version" -> "Mac and phone versions don't match; update Droidtosh on both"
                else -> "${error.code}: ${error.message}"
            }
        )
    }

    // Stamping and writing happen under one lock: if two threads could stamp seq 5 and 6 but write 6
    // first, the Mac would count a gap and then drop 5 as a duplicate.
    @Synchronized
    fun send(envelope: Envelope) {
        val stamped = reliable?.prepareOutgoing(envelope) ?: envelope
        val bytes = stamped.toByteArray()
        stream.writeFrame(bytes)
        Diagnostics.recorder.onFrameOut(bytes.size)
    }

    fun receive(): Envelope {
        val frame = stream.readFrameOrNull() ?: throw EOFException("connection closed")
        Diagnostics.recorder.onFrameIn(frame.size)
        val envelope = try {
            Envelope.parseFrom(frame)
        } catch (error: InvalidProtocolBufferException) {
            Diagnostics.recorder.onFrameRejected("unparseable ${frame.size}-byte frame")
            throw error
        }
        if (envelope.protocolVersion != negotiatedVersion) {
            Diagnostics.recorder.onFrameRejected("protocol_version ${envelope.protocolVersion}")
            throw ProtocolVersionException(envelope.protocolVersion)
        }
        return envelope
    }

    fun close() {
        stream.close()
    }
}
