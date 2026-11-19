package dev.southstar21.droidtosh.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveNotificationsTest {
    @Test
    fun englishPlaceholderIsRecognised() {
        assertTrue(SensitiveNotifications.isRedactionPlaceholder("Sensitive notification content hidden", null))
        assertTrue(SensitiveNotifications.isRedactionPlaceholder("  sensitive notification content hidden ", null))
    }

    @Test
    fun localisedSystemPlaceholderIsRecognised() {
        assertTrue(SensitiveNotifications.isRedactionPlaceholder("Contenu sensible masque", "Contenu sensible masque"))
    }

    @Test
    fun ordinaryTextIsNotRedacted() {
        assertFalse(SensitiveNotifications.isRedactionPlaceholder("Your code is 123456", "Contenu sensible masque"))
        assertFalse(SensitiveNotifications.isRedactionPlaceholder("", ""))
        assertFalse(SensitiveNotifications.isRedactionPlaceholder(null, null))
    }

    @Test
    fun adbCommandNamesThePackage() {
        assertEquals(
            "adb shell appops set --user 0 dev.southstar21.droidtosh RECEIVE_SENSITIVE_NOTIFICATIONS allow",
            SensitiveNotifications.adbCommand("dev.southstar21.droidtosh"),
        )
    }
}
