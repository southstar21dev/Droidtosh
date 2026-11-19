package dev.southstar21.droidtosh.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeTokenTest {
    @Test
    fun prefixesAnHttpsEndpoint() {
        assertEquals("up:https://ntfy.example.org/up1", WakeToken.unifiedPush("https://ntfy.example.org/up1"))
    }

    @Test
    fun rejectsNonHttpsOrBlankEndpoints() {
        assertNull(WakeToken.unifiedPush(null))
        assertNull(WakeToken.unifiedPush("http://ntfy.example.org/up1"))
        assertNull(WakeToken.unifiedPush("https://a.example/x y"))
    }

    @Test
    fun dropsAnEndpointTooLongForTheJoinLine() {
        val fits = "https://h.example/" + "a".repeat(WakeToken.MAX_TOKEN_FIELD - 3 - "https://h.example/".length)
        val tooLong = fits + "a"
        assertEquals("up:$fits", WakeToken.unifiedPush(fits))
        assertNull(WakeToken.unifiedPush(tooLong))
    }

    @Test
    fun fcmStaysTheDefaultWhereAvailable() {
        assertEquals(WakeProvider.FCM, WakeToken.effective(null, fcmAvailable = true, hasEndpoint = true))
        assertEquals(WakeProvider.UNIFIEDPUSH, WakeToken.effective(null, fcmAvailable = false, hasEndpoint = true))
        assertEquals(WakeProvider.NONE, WakeToken.effective(null, fcmAvailable = false, hasEndpoint = false))
    }

    @Test
    fun anExplicitChoiceWins() {
        assertEquals(
            WakeProvider.UNIFIEDPUSH,
            WakeToken.effective(WakeProvider.UNIFIEDPUSH, fcmAvailable = true, hasEndpoint = false),
        )
        assertEquals(WakeProvider.NONE, WakeToken.effective(WakeProvider.NONE, fcmAvailable = true, hasEndpoint = true))
        assertEquals(WakeProvider.NONE, WakeToken.effective(WakeProvider.FCM, fcmAvailable = false, hasEndpoint = false))
    }
}
