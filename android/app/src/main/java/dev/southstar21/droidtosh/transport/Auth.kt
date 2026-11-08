package dev.southstar21.droidtosh.transport

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

open class AuthError(message: String) : Exception(message) {
    object InvalidMessage : AuthError("invalid auth message")
    object InvalidMAC : AuthError("invalid mac")
}

object Auth {
    const val PSK_LENGTH = 32
    const val NONCE_LENGTH = 32

    fun macInput(role: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray =
        role.toByteArray(Charsets.UTF_8) + serverNonce + clientNonce

    fun mac(psk: ByteArray, role: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(psk, "HmacSHA256"))
        return mac.doFinal(macInput(role, serverNonce, clientNonce))
    }

    fun constantTimeEquals(lhs: ByteArray, rhs: ByteArray): Boolean = MessageDigest.isEqual(lhs, rhs)
}
