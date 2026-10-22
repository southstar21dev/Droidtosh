package dev.southstar21.droidtosh.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchPolicyTest {
    @Test
    fun launchDirectlyOnlyWhenVisible() {
        assertEquals(LaunchMode.DIRECT, LaunchPolicy.decide(appVisible = true))
        assertEquals(LaunchMode.DIRECT_WITH_NOTIFICATION, LaunchPolicy.decide(appVisible = false))
    }

    @Test
    fun overlayPermissionCountsAsVisible() {
        // "Display over other apps" exempts the app from the background activity start limits.
        assertEquals(LaunchMode.DIRECT, LaunchPolicy.decide(appVisible = false, canDrawOverlays = true))
        assertEquals(LaunchMode.DIRECT, LaunchPolicy.decide(appVisible = true, canDrawOverlays = true))
        assertEquals(
            LaunchMode.DIRECT_WITH_NOTIFICATION,
            LaunchPolicy.decide(appVisible = false, canDrawOverlays = false),
        )
    }
}
