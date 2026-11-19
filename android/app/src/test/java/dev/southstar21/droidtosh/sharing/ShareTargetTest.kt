package dev.southstar21.droidtosh.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShareTargetTest {
    @Test
    fun labelIsTheMacName() = assertEquals("MacBook Pro", ShareTarget.label("  MacBook Pro "))

    @Test
    fun labelFallsBackToMac() {
        assertEquals("Mac", ShareTarget.label(null))
        assertEquals("Mac", ShareTarget.label("   "))
    }

    @Test
    fun longNamesAreCut() = assertEquals(40, ShareTarget.label("x".repeat(100)).length)

    @Test
    fun idIsStablePerPairing() {
        val a = ShareTarget.shortcutId("aabbccddeeff00112233")
        assertEquals(a, ShareTarget.shortcutId("aabbccddeeff00112233"))
        assertNotEquals(a, ShareTarget.shortcutId("ffffffffffffffff0000"))
    }

    @Test
    fun noPairingMeansRemove() {
        assertEquals(ShareTargetPlan.Remove, ShareTarget.plan(null, "MacBook Pro"))
        assertEquals(ShareTargetPlan.Remove, ShareTarget.plan("", "MacBook Pro"))
    }

    @Test
    fun pairingMeansPublishWithNameOrFallback() {
        assertEquals(
            ShareTargetPlan.Publish("mac-abcdef0123456789", "Studio"),
            ShareTarget.plan("abcdef0123456789zz", "Studio"),
        )
        assertEquals(
            ShareTargetPlan.Publish("mac-abcdef", "Mac"),
            ShareTarget.plan("abcdef", null),
        )
    }
}
