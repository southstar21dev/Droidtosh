package dev.southstar21.droidtosh.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessageRouterTest {
    @Test
    fun macOnlineTriggersWake() {
        var woke = false
        val handled = PushMessageRouter.handle(mapOf("type" to "mac_online")) { woke = true }
        assertTrue(handled)
        assertTrue(woke)
    }

    @Test
    fun unknownTypeIsIgnored() {
        var woke = false
        assertFalse(PushMessageRouter.handle(mapOf("type" to "other")) { woke = true })
        assertFalse(woke)
    }

    @Test
    fun missingTypeIsIgnored() {
        var woke = false
        assertFalse(PushMessageRouter.handle(emptyMap()) { woke = true })
        assertFalse(woke)
    }
}
