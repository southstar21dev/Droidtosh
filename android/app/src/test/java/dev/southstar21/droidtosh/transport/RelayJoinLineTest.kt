package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayJoinLineTest {
    @Test
    fun formatsMac() {
        assertEquals("DROIDSYNC/2 R mac\n", RelayJoinLine.format("R", "mac", null))
    }

    @Test
    fun formatsPhoneWithToken() {
        assertEquals("DROIDSYNC/2 R phone tok\n", RelayJoinLine.format("R", "phone", "tok"))
    }

    @Test
    fun omitsBlankToken() {
        assertEquals("DROIDSYNC/2 R phone\n", RelayJoinLine.format("R", "phone", ""))
    }

    @Test
    fun parsesResponses() {
        assertEquals(RelayResponse.Paired, RelayResponse.parse("PAIRED\n"))
        assertEquals(RelayResponse.Waiting, RelayResponse.parse("WAIT"))
        assertEquals(RelayResponse.Error("bad-join"), RelayResponse.parse("ERROR bad-join\n"))
        assertEquals(RelayResponse.Error("upgrade"), RelayResponse.parse("ERROR upgrade\n"))
    }

    @Test
    fun upgradeErrorHasUserFacingMessage() {
        assertEquals(
            "Relay and app versions don't match; update the relay and Droidtosh",
            relayErrorMessage("upgrade"),
        )
        assertEquals("relay error: busy", relayErrorMessage("busy"))
    }

    @Test
    fun unknownResponseThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            RelayResponse.parse("SOMETHING\n")
        }
    }
}
