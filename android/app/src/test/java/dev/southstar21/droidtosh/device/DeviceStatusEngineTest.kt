package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.protocol.v1.DeviceStatus
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import dev.southstar21.droidtosh.transport.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusEngineTest {
    private var now = 1_000_000L
    private var current = PhoneStatusSnapshot(
        batteryPercent = 80,
        networkType = NetworkType.NETWORK_TYPE_WIFI,
        wifiLevel = 3,
        ringerMode = RingerMode.RINGER_MODE_NORMAL,
    )
    private val sent = mutableListOf<Envelope>()
    private var accept = true

    private val source = object : DeviceStatusSource {
        override fun snapshot() = current
    }
    private val engine = DeviceStatusEngine(
        source = source,
        send = { if (accept) sent.add(it) else false },
        clock = { now },
    )
    private val withStatus = setOf(Capabilities.PHONE_STATUS, Capabilities.RELIABLE)

    private val statuses: List<DeviceStatus> get() = sent.map { it.deviceStatus }

    /** Advances the clock by [ms] in 100 ms steps, ticking whenever the engine says something is due. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (now < end) {
            now = minOf(end, now + 100)
            val wait = engine.nextWakeInMs()
            if (wait != null && wait == 0L) engine.tick()
        }
    }

    private fun change(block: (PhoneStatusSnapshot) -> PhoneStatusSnapshot) {
        current = block(current)
        engine.onSourceChanged()
    }

    private fun connected() {
        engine.onSessionStarted(withStatus)
        assertTrue(engine.tick())
        sent.clear()
    }

    @Test
    fun sendsOnceRightAfterEachSessionStarts() {
        engine.onSessionStarted(withStatus)
        assertEquals(0L, engine.nextWakeInMs())
        assertTrue(engine.tick())
        assertFalse(engine.tick())
        assertEquals(1, sent.size)
        val status = statuses.single()
        assertEquals(80, status.batteryPercent)
        assertEquals(NetworkType.NETWORK_TYPE_WIFI, status.networkType)
        assertEquals(now, status.sampledAtMs)

        engine.onSessionEnded()
        engine.onSessionStarted(withStatus)
        assertTrue(engine.tick())
        assertEquals(2, sent.size)
    }

    @Test
    fun doesNothingWithoutThePhoneStatusCapability() {
        engine.onSessionStarted(setOf(Capabilities.RELIABLE, Capabilities.FILES))
        assertNull(engine.nextWakeInMs())
        assertFalse(engine.tick())
        change { it.copy(charging = true) }
        advance(10 * 60_000L)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun doesNothingWhileDisconnected() {
        change { it.copy(charging = true) }
        assertNull(engine.nextWakeInMs())
        advance(10 * 60_000L)
        assertTrue(sent.isEmpty())

        connected()
        engine.onSessionEnded()
        change { it.copy(batteryPercent = 10) }
        advance(10 * 60_000L)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun slowChangesAreCoalescedToOnePerTenSeconds() {
        connected()
        advance(3_000)
        change { it.copy(batteryPercent = 79) }
        advance(2_000)
        change { it.copy(batteryPercent = 78) }
        change { it.copy(wifiLevel = 2) }
        // 10 s after the last send: one envelope with the latest values.
        advance(4_900)
        assertTrue(sent.isEmpty())
        advance(200)
        assertEquals(1, sent.size)
        assertEquals(78, statuses.last().batteryPercent)
        assertEquals(2, statuses.last().wifiLevel)

        // The next change waits a full 10 s from that send.
        change { it.copy(batteryPercent = 77) }
        advance(9_800)
        assertEquals(1, sent.size)
        advance(300)
        assertEquals(2, sent.size)
        assertEquals(77, statuses.last().batteryPercent)
    }

    @Test
    fun aSlowChangeLongAfterTheLastSendGoesOutRightAway() {
        connected()
        advance(60_000)
        change { it.copy(batteryPercent = 70) }
        advance(100)
        assertEquals(1, sent.size)
    }

    @Test
    fun fastFieldsAreSentWithinOneSecond() {
        val changes = listOf<(PhoneStatusSnapshot) -> PhoneStatusSnapshot>(
            { it.copy(charging = !it.charging) },
            { it.copy(networkType = NetworkType.NETWORK_TYPE_CELLULAR) },
            { it.copy(airplaneMode = !it.airplaneMode) },
            { it.copy(ringerMode = RingerMode.RINGER_MODE_SILENT) },
            { it.copy(doNotDisturb = !it.doNotDisturb) },
        )
        connected()
        for (block in changes) {
            val before = sent.size
            // Right after a send, so a slow change would have to wait ~10 s.
            change(block)
            advance(1_000)
            assertEquals(before + 1, sent.size)
            assertEquals(current.toProto(0).toBuilder().clearSampledAtMs().build(),
                statuses.last().toBuilder().clearSampledAtMs().build())
        }
    }

    @Test
    fun aFastChangeCarriesPendingSlowChangesWithIt() {
        connected()
        change { it.copy(batteryPercent = 50) }
        advance(300)
        change { it.copy(charging = true) }
        advance(1_000)
        assertEquals(1, sent.size)
        assertEquals(50, statuses.single().batteryPercent)
        assertTrue(statuses.single().charging)
        // Nothing left pending afterwards.
        advance(30_000)
        assertEquals(1, sent.size)
    }

    @Test
    fun aChangeThatRevertsIsNotSent() {
        connected()
        change { it.copy(batteryPercent = 79) }
        change { it.copy(batteryPercent = 80) }
        advance(60_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun unchangedSourceNotificationsSendNothing() {
        connected()
        repeat(10) { engine.onSourceChanged() }
        advance(60_000)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun heartbeatAtLeastEveryFiveMinutes() {
        connected()
        advance(5 * 60_000L - 200)
        assertTrue(sent.isEmpty())
        advance(300)
        assertEquals(1, sent.size)
        advance(5 * 60_000L)
        assertEquals(2, sent.size)
        // Sleeps until the next heartbeat while nothing changes.
        assertTrue(engine.nextWakeInMs()!! > 4 * 60_000L)
    }

    @Test
    fun aChangeResetsTheHeartbeat() {
        connected()
        advance(4 * 60_000L)
        change { it.copy(charging = true) }
        advance(1_000)
        assertEquals(1, sent.size)
        advance(4 * 60_000L)
        assertEquals(1, sent.size)
        advance(60_000L)
        assertEquals(2, sent.size)
    }

    @Test
    fun aRefusedSendIsRetried() {
        accept = false
        engine.onSessionStarted(withStatus)
        assertFalse(engine.tick())
        assertEquals(DeviceStatusEngine.RETRY_MS, engine.nextWakeInMs())
        accept = true
        advance(DeviceStatusEngine.RETRY_MS)
        assertEquals(1, sent.size)
    }

    @Test
    fun aNewSessionSendsEvenIfNothingChanged() {
        connected()
        engine.onSessionStarted(withStatus)
        assertTrue(engine.tick())
        assertEquals(1, sent.size)
    }
}
