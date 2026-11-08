package dev.southstar21.droidtosh.calls

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoipActionMatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val matcher = VoipActionMatcher(VoipProfiles.load(context), VoipProfiles.generic(context))

    private fun action(title: String): Notification.Action =
        Notification.Action.Builder(
            0,
            title,
            PendingIntent.getBroadcast(context, title.hashCode(), Intent(), PendingIntent.FLAG_IMMUTABLE),
        ).build()

    @Test
    fun matchesWhatsAppEnglishActions() {
        val matches = matcher.match(
            "com.whatsapp",
            listOf(action("Decline"), action("Answer")),
        )
        assertEquals(1, matches[VoipAction.ACCEPT])
        assertEquals(0, matches[VoipAction.DECLINE])
    }

    @Test
    fun matchesEndActionForOngoingCall() {
        val matches = matcher.match("org.telegram.messenger", listOf(action("Hang up")))
        assertEquals(0, matches[VoipAction.END])
    }

    @Test
    fun matchesGenericPatternsForUnknownPackage() {
        val matches = matcher.match("com.example.voip", listOf(action("Accept"), action("Reject")))
        assertEquals(0, matches[VoipAction.ACCEPT])
        assertEquals(1, matches[VoipAction.DECLINE])
    }

    @Test
    fun doesNotMatchPatternsInsideOtherWords() {
        val matches = matcher.match(
            "com.example.mail",
            listOf(action("Send"), action("Add to calendar"), action("Attend")),
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun genericTableIsSkippedWhenNotAllowed() {
        val actions = listOf(action("Accept"), action("Dismiss"))
        assertTrue(matcher.match("com.example.social", actions, allowGeneric = false).isEmpty())
        assertEquals(0, matcher.match("com.example.social", actions)[VoipAction.ACCEPT])
    }

    @Test
    fun persianRejectIsNotTreatedAsAccept() {
        val matches = matcher.match("com.example.voip", listOf(action("رد کردن")))
        assertEquals(0, matches[VoipAction.DECLINE])
        assertEquals(null, matches[VoipAction.ACCEPT])
    }

    @Test
    fun matchesWordHandlesBoundariesAndCjk() {
        assertTrue(VoipActionMatcher.matchesWord("end call", "end"))
        assertTrue(VoipActionMatcher.matchesWord("end", "end"))
        assertTrue(!VoipActionMatcher.matchesWord("send", "end"))
        assertTrue(!VoipActionMatcher.matchesWord("weekend", "end"))
        assertTrue(VoipActionMatcher.matchesWord("接听来电", "接听"))
    }

    @Test
    fun returnsEmptyForNonMatchingTitles() {
        assertTrue(matcher.match("com.whatsapp", listOf(action("Reply"), action("Mark read"))).isEmpty())
    }
}
