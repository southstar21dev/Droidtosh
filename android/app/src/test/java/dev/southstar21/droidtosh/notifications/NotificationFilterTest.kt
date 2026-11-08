package dev.southstar21.droidtosh.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val filter = NotificationFilter({ setOf("com.spammy") }, ownPackage = "dev.southstar21.droidtosh")

    @Test
    fun forwardsNormalNotification() {
        assertTrue(filter.shouldForward("com.whatsapp", isGroupSummary = false))
    }

    @Test
    fun skipsOwnNotifications() {
        assertFalse(filter.shouldForward("dev.southstar21.droidtosh", isGroupSummary = false))
    }

    @Test
    fun skipsGroupSummaries() {
        assertFalse(filter.shouldForward("com.whatsapp", isGroupSummary = true))
    }

    @Test
    fun skipsMutedPackages() {
        assertFalse(filter.shouldForward("com.spammy", isGroupSummary = false))
    }

    @Test
    fun skipsSystemUiNoise() {
        assertFalse(filter.shouldForward("com.android.systemui", isGroupSummary = false))
        assertTrue(NotificationFilter.isSystemNoise("com.android.systemui"))
        assertFalse(NotificationFilter.isSystemNoise("com.whatsapp"))
    }

    @Test
    fun skipsSamsungRoutinesNoise() {
        assertFalse(filter.shouldForward("com.samsung.android.app.routines", isGroupSummary = false))
        assertTrue(NotificationFilter.isSystemNoise("com.samsung.android.app.routines"))
    }
}
