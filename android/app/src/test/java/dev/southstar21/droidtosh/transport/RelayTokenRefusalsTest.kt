package dev.southstar21.droidtosh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RelayTokenRefusalsTest {
    private class MapStore : RelayTokenRefusals.Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private val relay = RelayTokenRefusals.relayKey("relay.example", 7443, "AB:CD")
    private val token = "up:https://push.example/" + "x".repeat(300)

    @Test
    fun retriesOnlyWhenATokenWasSentAndTheRelayAnsweredBadJoin() {
        val badJoin = RelayConnectResult.Failed(RelayErrorException("bad-join"))
        assertTrue(RelayTokenRefusals.shouldRetryWithoutToken(true, badJoin))
        assertFalse(RelayTokenRefusals.shouldRetryWithoutToken(false, badJoin))
        assertFalse(RelayTokenRefusals.shouldRetryWithoutToken(true, RelayConnectResult.Failed(RelayErrorException("upgrade"))))
        assertFalse(RelayTokenRefusals.shouldRetryWithoutToken(true, RelayConnectResult.Failed(IOException("timeout"))))
        assertFalse(RelayTokenRefusals.shouldRetryWithoutToken(true, RelayConnectResult.Waiting))
    }

    @Test
    fun aRefusedTokenIsLeftOutForThatRelayOnly() {
        val refusals = RelayTokenRefusals(MapStore(), "1.0")
        assertEquals(token, refusals.tokenFor(relay, token))
        refusals.remember(relay, token)
        assertNull(refusals.tokenFor(relay, token))
        val other = RelayTokenRefusals.relayKey("other.example", 7443, "AB:CD")
        val repinned = RelayTokenRefusals.relayKey("relay.example", 7443, "EF:01")
        assertEquals(token, refusals.tokenFor(other, token))
        assertEquals(token, refusals.tokenFor(repinned, token))
    }

    @Test
    fun aNewTokenOrANewAppVersionTriesAgain() {
        val store = MapStore()
        RelayTokenRefusals(store, "1.0").remember(relay, token)
        assertTrue(RelayTokenRefusals(store, "1.0").refused(relay, token))
        assertFalse(RelayTokenRefusals(store, "1.0").refused(relay, "up:https://push.example/other"))
        assertFalse(RelayTokenRefusals(store, "1.1").refused(relay, token))
    }

    @Test
    fun aRefusalExpiresAfterADaySoAnUpdatedRelayGetsTheTokenAgain() {
        val store = MapStore()
        var now = 1_000_000L
        val refusals = RelayTokenRefusals(store, "1.0", clock = { now })
        refusals.remember(relay, token)
        now += RelayTokenRefusals.RETRY_AFTER_MS - 1
        assertNull(refusals.tokenFor(relay, token))
        now += 1
        assertEquals(token, refusals.tokenFor(relay, token))
    }

    @Test
    fun theTokenItselfIsNotStored() {
        val store = MapStore()
        RelayTokenRefusals(store, "1.0").remember(relay, token)
        assertTrue(store.map.values.none { it.contains("push.example") })
    }

    @Test
    fun noTokenIsNeverRefusedAndForgetClears() {
        val refusals = RelayTokenRefusals(MapStore(), "1.0")
        assertNull(refusals.tokenFor(relay, null))
        assertNull(refusals.tokenFor(relay, ""))
        assertFalse(refusals.refused(relay, null))
        refusals.remember(relay, token)
        refusals.forget(relay)
        assertFalse(refusals.refused(relay, token))
    }
}
