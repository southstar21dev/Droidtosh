package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionNotificationTextTest {
    private fun asleep() = ConnectionState.Disconnected("Mac asleep", DisconnectCause.MacAsleepOrOffline)

    @Test
    fun macAsleepWithoutNameSaysMacIsAsleep() {
        val content = ConnectionNotificationText.forState(asleep(), "Waiting for Mac")!!
        assertEquals("Mac is asleep", content.title)
        assertEquals("Connects when it wakes up or Droidtosh opens", content.text)
    }

    @Test
    fun macAsleepUsesKnownName() {
        val content = ConnectionNotificationText.forState(asleep(), "Waiting for Mac", "MacBook Pro")!!
        assertEquals("MacBook Pro is asleep", content.title)
    }

    @Test
    fun otherCausesGetPlainWords() {
        fun title(cause: DisconnectCause) =
            ConnectionNotificationText.forState(ConnectionState.Disconnected("x", cause), "Disconnected - reconnecting")!!.title
        assertEquals("Relay unreachable", title(DisconnectCause.RelayUnreachable))
        assertEquals("No Wi-Fi", title(DisconnectCause.NoWifiLocal))
        assertEquals("Pairing expired", title(DisconnectCause.PairingExpired))
    }

    @Test
    fun otherStatesKeepTheirStatusText() {
        assertEquals(
            NotificationContent("Droidtosh", "Connecting"),
            ConnectionNotificationText.forState(ConnectionState.Connecting, "Connecting"),
        )
        assertEquals(
            NotificationContent("Droidtosh", "Connected via relay"),
            ConnectionNotificationText.forState(ConnectionState.Connected("Pixel"), "Connected via relay"),
        )
        assertEquals(
            NotificationContent("Droidtosh", "Disconnected - reconnecting"),
            ConnectionNotificationText.forState(
                ConnectionState.Disconnected("x", DisconnectCause.Other), "Disconnected - reconnecting",
            ),
        )
    }

    @Test
    fun noStatusTextAndNoSpecificCauseMeansNoChange() {
        assertNull(ConnectionNotificationText.forState(ConnectionState.NotPaired, null))
    }
}
