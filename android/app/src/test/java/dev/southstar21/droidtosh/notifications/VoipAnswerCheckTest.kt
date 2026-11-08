package dev.southstar21.droidtosh.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipAnswerCheckTest {
    @Test
    fun answeredCallIsOk() {
        val outcome = VoipAnswerCheck.outcome(answered = true, mayStartActivities = false, keyguardLocked = false, appLabel = "WhatsApp")
        assertTrue(outcome.ok)
    }

    @Test
    fun unlockedWithoutOverlayPermissionIsReportedAsBlocked() {
        val outcome = VoipAnswerCheck.outcome(answered = false, mayStartActivities = false, keyguardLocked = false, appLabel = "WhatsApp")
        assertFalse(outcome.ok)
        assertEquals(VoipAnswerCheck.BLOCKED, outcome.errorCode)
        assertTrue(outcome.message.contains("display over other apps"))
        assertTrue(VoipAnswerCheck.likelyBlocked(mayStartActivities = false, keyguardLocked = false))
    }

    @Test
    fun otherFailuresAreReportedAsNoEffect() {
        // Locked: the app's full-screen call screen lets the start through, so it's not BAL.
        val locked = VoipAnswerCheck.outcome(answered = false, mayStartActivities = false, keyguardLocked = true, appLabel = "WhatsApp")
        assertEquals(VoipAnswerCheck.NO_EFFECT, locked.errorCode)
        // Allowed to draw over other apps: exempt from the background start limits.
        val allowed = VoipAnswerCheck.outcome(answered = false, mayStartActivities = true, keyguardLocked = false, appLabel = "WhatsApp")
        assertEquals(VoipAnswerCheck.NO_EFFECT, allowed.errorCode)
        assertFalse(VoipAnswerCheck.likelyBlocked(mayStartActivities = true, keyguardLocked = false))
        assertFalse(VoipAnswerCheck.likelyBlocked(mayStartActivities = false, keyguardLocked = true))
    }
}
