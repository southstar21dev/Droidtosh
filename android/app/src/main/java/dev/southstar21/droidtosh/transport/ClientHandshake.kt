package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.AuthResponse
import dev.southstar21.droidtosh.protocol.v1.Envelope
import com.google.protobuf.ByteString
import java.security.SecureRandom

class ClientHandshake(
    private val psk: ByteArray,
    private val nonceGenerator: () -> ByteArray = { randomNonce() },
    private val capabilities: List<String> = Capabilities.SUPPORTED,
) {
    sealed interface State {
        data object Initial : State
        data class AwaitingAuthOk(val serverNonce: ByteArray, val clientNonce: ByteArray) : State
        data class Authenticated(val version: Int, val capabilities: Set<String>) : State
        data object Failed : State
    }

    var state: State = State.Initial
        private set

    fun handleChallenge(envelope: Envelope): Envelope {
        val challenge = if (state == State.Initial && envelope.hasAuthChallenge()) {
            envelope.authChallenge
        } else {
            null
        }
        if (challenge == null || challenge.serverNonce.size() != Auth.NONCE_LENGTH) {
            state = State.Failed
            throw AuthError.InvalidMessage
        }
        // The Mac picks the version, but fail early when the two ranges can't overlap.
        if (challenge.maxVersion < MIN_PROTOCOL_VERSION || challenge.minVersion > MAX_PROTOCOL_VERSION) {
            state = State.Failed
            throw ProtocolVersionException(challenge.maxVersion)
        }
        val serverNonce = challenge.serverNonce.toByteArray()
        val clientNonce = nonceGenerator()
        state = State.AwaitingAuthOk(serverNonce, clientNonce)
        return newEnvelope {
            setAuthResponse(
                AuthResponse.newBuilder()
                    .setClientNonce(ByteString.copyFrom(clientNonce))
                    .setMac(ByteString.copyFrom(Auth.mac(psk, "client", serverNonce, clientNonce)))
                    .setMinVersion(MIN_PROTOCOL_VERSION)
                    .setMaxVersion(MAX_PROTOCOL_VERSION)
                    .addAllCapabilities(capabilities)
            )
        }
    }

    fun handleAuthOk(envelope: Envelope) {
        val awaiting = state as? State.AwaitingAuthOk
        if (awaiting == null || !envelope.hasAuthOk()) {
            state = State.Failed
            throw AuthError.InvalidMessage
        }
        val expected = Auth.mac(psk, "server", awaiting.serverNonce, awaiting.clientNonce)
        if (!Auth.constantTimeEquals(expected, envelope.authOk.mac.toByteArray())) {
            state = State.Failed
            throw AuthError.InvalidMAC
        }
        val version = envelope.authOk.version
        if (version !in MIN_PROTOCOL_VERSION..MAX_PROTOCOL_VERSION) {
            state = State.Failed
            throw ProtocolVersionException(version)
        }
        // Only trust capabilities this side also listed, even if the Mac's intersection is off.
        val negotiated = envelope.authOk.capabilitiesList.toSet().intersect(capabilities.toSet())
        state = State.Authenticated(version, negotiated)
    }
}

fun randomNonce(): ByteArray =
    ByteArray(Auth.NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
