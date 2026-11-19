package dev.southstar21.droidtosh.transport

import java.io.IOException

/**
 * Why the phone is not connected, in terms the user can act on. Set where the connection loop knows
 * the reason; the screens turn it into plain words.
 */
enum class DisconnectCause {
    /** The Mac is asleep, or Droidtosh is not running on it; the relay has no Mac parked. */
    MacAsleepOrOffline,
    RelayUnreachable,
    /** The relay presented a certificate other than the one in the pairing. */
    RelayCertificate,
    /** Local mode and the phone has no Wi-Fi or Ethernet network. */
    NoWifiLocal,
    /** Local mode, on a network, but the Mac did not answer there. */
    MacNotOnNetwork,
    PairingExpired,
    VersionMismatch,
    /**
     * An established session dropped, or the relay answered WAIT during the short grace after a start or
     * a dropped session (the Mac has not re-parked yet); the loop is dialling again.
     */
    ConnectionLost,
    Other,
}

object DisconnectCauses {
    /**
     * The cause of a failed attempt or a dropped session. [mode] is the pairing's connection mode and
     * [wasConnected] whether the failure ended an authenticated session.
     */
    fun fromError(error: Throwable, mode: ConnectionMode, wasConnected: Boolean): DisconnectCause {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("Pairing expired") -> DisconnectCause.PairingExpired
            error is ProtocolVersionException || message.startsWith("Mac and phone versions") ->
                DisconnectCause.VersionMismatch
            error is RelayTlsException -> DisconnectCause.RelayCertificate
            wasConnected && error is IOException -> DisconnectCause.ConnectionLost
            error is IOException || error is javax.net.ssl.SSLException ->
                if (mode == ConnectionMode.LOCAL) DisconnectCause.MacNotOnNetwork else DisconnectCause.RelayUnreachable
            else -> DisconnectCause.Other
        }
    }
}
