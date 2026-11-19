package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPlanTest {
    @Test
    fun localModeWaitsWithoutWifiAndDialsWithIt() {
        val without = LocalNetworkPlan.decide(ConnectionMode.LOCAL, hasRelay = false, localNetworkAvailable = false)
        assertTrue(without.waitForLocalNetwork)
        assertFalse(without.dialLocal)
        val with = LocalNetworkPlan.decide(ConnectionMode.LOCAL, hasRelay = false, localNetworkAvailable = true)
        assertFalse(with.waitForLocalNetwork)
        assertTrue(with.dialLocal)
    }

    @Test
    fun localModeIgnoresTheRelayEvenIfThePairingHasOne() {
        val plan = LocalNetworkPlan.decide(ConnectionMode.LOCAL, hasRelay = true, localNetworkAvailable = false)
        assertFalse(plan.dialRelay)
        assertTrue(plan.waitForLocalNetwork)
    }

    @Test
    fun bothModeSkipsOnlyTheLocalCandidatesWithoutWifi() {
        val plan = LocalNetworkPlan.decide(ConnectionMode.BOTH, hasRelay = true, localNetworkAvailable = false)
        assertTrue(plan.dialRelay)
        assertFalse(plan.dialLocal)
        assertFalse(plan.waitForLocalNetwork)
        assertEquals(
            LocalNetworkPlan.Plan(dialRelay = true, dialLocal = true),
            LocalNetworkPlan.decide(ConnectionMode.BOTH, hasRelay = true, localNetworkAvailable = true),
        )
    }

    @Test
    fun bothModeWithoutARelayBehavesLikeLocal() {
        assertTrue(LocalNetworkPlan.decide(ConnectionMode.BOTH, hasRelay = false, localNetworkAvailable = false).waitForLocalNetwork)
        assertTrue(LocalNetworkPlan.decide(ConnectionMode.BOTH, hasRelay = false, localNetworkAvailable = true).dialLocal)
    }

    @Test
    fun relayModeNeverNeedsALocalNetwork() {
        val plan = LocalNetworkPlan.decide(ConnectionMode.RELAY, hasRelay = true, localNetworkAvailable = false)
        assertTrue(plan.dialRelay)
        assertFalse(plan.dialLocal)
        assertFalse(plan.waitForLocalNetwork)
    }

    @Test
    fun trackerFollowsNetworksComingAndGoing() {
        val tracker = LocalNetworkTracker()
        assertFalse(tracker.available)
        assertTrue(tracker.onAvailable("wifi"))
        assertFalse("a second network is not a change", tracker.onAvailable("ethernet"))
        assertFalse(tracker.onLost("wifi"))
        assertTrue(tracker.available)
        assertTrue(tracker.onLost("ethernet"))
        assertFalse(tracker.available)
        assertFalse("losing an unknown network changes nothing", tracker.onLost("ghost"))
    }

    @Test
    fun trackerCanBeSeededWithTheNetworksThatAreAlreadyUp() {
        val tracker = LocalNetworkTracker()
        tracker.reset(listOf("wifi"))
        assertTrue(tracker.available)
        tracker.reset(emptyList())
        assertFalse(tracker.available)
    }
}
