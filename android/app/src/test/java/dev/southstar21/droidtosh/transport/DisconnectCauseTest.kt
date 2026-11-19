package dev.southstar21.droidtosh.transport

import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectCauseTest {
    private fun cause(error: Throwable, mode: ConnectionMode = ConnectionMode.RELAY, wasConnected: Boolean = false) =
        DisconnectCauses.fromError(error, mode, wasConnected)

    @Test
    fun anExpiredPairingIsReportedByTheMacAsInvalidPsk() {
        // ProtocolClient turns the Mac's invalid_psk error into this message.
        val error = IllegalStateException("Pairing expired; re-pair with the Mac")
        assertEquals(DisconnectCause.PairingExpired, cause(error))
        assertEquals(DisconnectCause.PairingExpired, cause(error, ConnectionMode.LOCAL))
    }

    @Test
    fun versionMismatchesAreExplained() {
        assertEquals(DisconnectCause.VersionMismatch, cause(ProtocolVersionException(3)))
        assertEquals(
            DisconnectCause.VersionMismatch,
            cause(IllegalStateException("Mac and phone versions don't match; update Droidtosh on both")),
        )
    }

    @Test
    fun aRelayPinFailureIsACertificateProblem() {
        assertEquals(DisconnectCause.RelayCertificate, cause(RelayTlsException("pin", SSLException("x"))))
    }

    @Test
    fun networkFailuresBlameTheRelayOrTheLocalNetworkDependingOnTheMode() {
        assertEquals(DisconnectCause.RelayUnreachable, cause(UnknownHostException("relay.example.com")))
        assertEquals(DisconnectCause.RelayUnreachable, cause(IOException("timeout"), ConnectionMode.BOTH))
        assertEquals(DisconnectCause.MacNotOnNetwork, cause(IOException("no route"), ConnectionMode.LOCAL))
    }

    @Test
    fun aDroppedSessionIsAConnectionLossNotARelayProblem() {
        assertEquals(DisconnectCause.ConnectionLost, cause(EOFException("connection closed"), wasConnected = true))
        assertEquals(DisconnectCause.ConnectionLost, cause(IOException("reset"), ConnectionMode.LOCAL, wasConnected = true))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertEquals(DisconnectCause.Other, cause(RuntimeException("boom")))
    }
}
