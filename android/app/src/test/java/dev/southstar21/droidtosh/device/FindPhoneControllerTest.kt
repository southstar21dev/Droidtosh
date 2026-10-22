package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindAction
import dev.southstar21.droidtosh.protocol.v1.FindEndReason
import dev.southstar21.droidtosh.protocol.v1.FindPhoneRequest
import dev.southstar21.droidtosh.protocol.v1.FindPhoneState
import dev.southstar21.droidtosh.transport.newEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPhoneControllerTest {
    private var now = 2_000_000_000L
    private var enabled = true
    private val sent = mutableListOf<Envelope>()

    private class FakeAlarm : Alarm {
        var works = true
        var starts = 0
        var stops = 0
        val ringing get() = starts > stops
        override fun start(): Boolean {
            if (!works) return false
            starts += 1
            return true
        }
        override fun stop() {
            stops += 1
        }
    }

    private val alarm = FakeAlarm()
    private val controller = FindPhoneController(
        send = { sent.add(it) },
        alarm = alarm,
        enabled = { enabled },
        clock = { now },
    )

    private val states: List<FindPhoneState> get() = sent.map { it.findPhoneState }

    private fun request(id: String, action: FindAction, sentAt: Long = now) = newEnvelope {
        setFindPhoneRequest(FindPhoneRequest.newBuilder().setRequestId(id).setAction(action))
        setSentAtMs(sentAt)
    }

    private fun start(id: String, sentAt: Long = now) = controller.onEnvelope(request(id, FindAction.FIND_ACTION_START, sentAt))

    private fun stop(id: String) = controller.onEnvelope(request(id, FindAction.FIND_ACTION_STOP))

    private fun advance(ms: Long) {
        now += ms
        controller.tick()
    }

    private fun assertState(state: FindPhoneState, id: String, ringing: Boolean, ended: FindEndReason, error: String = "") {
        assertEquals(id, state.requestId)
        assertEquals(ringing, state.ringing)
        assertEquals(ended, state.ended)
        assertEquals(error, state.error)
    }

    @Test
    fun startRingsAndReportsRinging() {
        start("a")
        assertTrue(alarm.ringing)
        assertTrue(controller.ringing.value)
        assertState(states.single(), "a", ringing = true, FindEndReason.FIND_END_UNSPECIFIED)
        assertEquals(FindPhoneController.RING_MS, controller.nextWakeInMs())
    }

    @Test
    fun ringsForSixtySecondsThenTimesOut() {
        start("a")
        advance(59_999)
        assertTrue(alarm.ringing)
        advance(1)
        assertFalse(alarm.ringing)
        assertFalse(controller.ringing.value)
        assertState(states.last(), "a", ringing = false, FindEndReason.FIND_END_TIMEOUT)
        assertNull(controller.nextWakeInMs())
    }

    @Test
    fun stopFromTheMacStopsTheRingWhateverItsId() {
        start("a")
        advance(5_000)
        stop("some-other-id")
        assertFalse(alarm.ringing)
        assertState(states.last(), "a", ringing = false, FindEndReason.FIND_END_STOPPED_FROM_MAC)
    }

    @Test
    fun stopReusingTheStartIdStillStops() {
        start("a")
        stop("a")
        assertFalse(alarm.ringing)
        assertState(states.last(), "a", ringing = false, FindEndReason.FIND_END_STOPPED_FROM_MAC)
    }

    @Test
    fun stopWhileIdleIsIgnored() {
        stop("x")
        assertTrue(sent.isEmpty())
        assertEquals(0, alarm.stops)
    }

    @Test
    fun stopOnPhoneEndsTheRing() {
        start("a")
        controller.stopOnPhone()
        assertFalse(alarm.ringing)
        assertState(states.last(), "a", ringing = false, FindEndReason.FIND_END_STOPPED_ON_PHONE)
        // A second press does nothing.
        controller.stopOnPhone()
        assertEquals(2, sent.size)
    }

    @Test
    fun startWhileRingingRestartsTheTimerAndAdoptsTheNewId() {
        start("a")
        advance(50_000)
        start("b")
        assertEquals(1, alarm.starts)
        assertState(states.last(), "b", ringing = true, FindEndReason.FIND_END_UNSPECIFIED)
        advance(59_000)
        assertTrue(alarm.ringing)
        advance(1_000)
        assertFalse(alarm.ringing)
        assertState(states.last(), "b", ringing = false, FindEndReason.FIND_END_TIMEOUT)
    }

    @Test
    fun eachRequestIdIsHandledOnce() {
        start("a")
        controller.stopOnPhone()
        // A retransmission of the same START after a reconnect must not ring again.
        start("a")
        assertEquals(1, alarm.starts)
        assertEquals(2, sent.size)

        // Nor does a duplicate STOP stop a later ring.
        stop("s")
        start("b")
        stop("s")
        assertTrue(alarm.ringing)
    }

    @Test
    fun aNewControllerForgetsHandledIds() {
        start("a")
        controller.stopOnPhone()
        val restarted = FindPhoneController(send = { sent.add(it) }, alarm = alarm, clock = { now })
        restarted.onEnvelope(request("a", FindAction.FIND_ACTION_START))
        assertTrue(alarm.ringing)
    }

    @Test
    fun staleStartIsRefused() {
        start("old", sentAt = now - 90_001)
        assertFalse(alarm.ringing)
        assertEquals(0, alarm.starts)
        assertState(states.single(), "old", ringing = false, FindEndReason.FIND_END_FAILED, "stale")
    }

    @Test
    fun startWithinNinetySecondsRings() {
        start("recent", sentAt = now - 90_000)
        assertTrue(alarm.ringing)
    }

    @Test
    fun unstampedStartIsNotStale() {
        start("unstamped", sentAt = 0)
        assertTrue(alarm.ringing)
    }

    @Test
    fun disabledSettingRefuses() {
        enabled = false
        start("a")
        assertEquals(0, alarm.starts)
        assertState(states.single(), "a", ringing = false, FindEndReason.FIND_END_FAILED, "disabled")
    }

    @Test
    fun audioFailureIsReported() {
        alarm.works = false
        start("a")
        assertFalse(controller.ringing.value)
        assertState(states.single(), "a", ringing = false, FindEndReason.FIND_END_FAILED, "audio_failed")
        assertNull(controller.nextWakeInMs())
    }

    @Test
    fun alarmThatThrowsCountsAsAudioFailure() {
        val throwing = FindPhoneController(
            send = { sent.add(it) },
            alarm = object : Alarm {
                override fun start(): Boolean = error("no audio")
                override fun stop() = Unit
            },
            clock = { now },
        )
        throwing.onEnvelope(request("a", FindAction.FIND_ACTION_START))
        assertState(states.single(), "a", ringing = false, FindEndReason.FIND_END_FAILED, "audio_failed")
    }

    @Test
    fun playbackErrorMidRingEndsItAsFailed() {
        start("a")
        controller.onAlarmFailed()
        assertFalse(alarm.ringing)
        assertState(states.last(), "a", ringing = false, FindEndReason.FIND_END_FAILED, "audio_failed")
    }

    @Test
    fun unspecifiedActionIsIgnored() {
        controller.onEnvelope(request("a", FindAction.FIND_ACTION_UNSPECIFIED))
        assertTrue(sent.isEmpty())
        // ...and doesn't use up the id.
        start("a")
        assertTrue(alarm.ringing)
    }

    @Test
    fun shutdownSilencesWithoutReporting() {
        start("a")
        controller.shutdown()
        assertFalse(alarm.ringing)
        assertEquals(1, sent.size)
    }
}
