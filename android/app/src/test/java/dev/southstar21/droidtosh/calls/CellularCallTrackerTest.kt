package dev.southstar21.droidtosh.calls

import android.content.Context
import android.telephony.TelephonyManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.CallDirection
import dev.southstar21.droidtosh.protocol.v1.CallKind
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import dev.southstar21.droidtosh.protocol.v1.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellularCallTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sent = mutableListOf<CallState>()
    private var simCall = true
    private var now = 1_000_000L
    private val scheduled = mutableListOf<() -> Unit>()
    private val contacts = mutableMapOf("+441234567890" to "Alice")
    private var callLogEntry: OutgoingLogEntry? = null
    private var callLogQueries = 0
    private val tracker = CellularCallTracker(
        context,
        simCallCheck = { simCall },
        sendState = { sent.add(it) },
        clock = { now },
        postDelayed = { delayMs, block ->
            scheduled.add {
                now += delayMs
                block()
            }
        },
        contactLookup = { contacts[it].orEmpty() },
        outgoingLogLookup = {
            callLogQueries++
            callLogEntry
        },
    )

    private fun phases() = sent.map { it.phase }

    /** Runs the delayed work, including work it schedules in turn. */
    private fun runScheduled() {
        while (scheduled.isNotEmpty()) scheduled.removeAt(0).invoke()
    }

    private fun offhook(number: String? = null) =
        tracker.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, number)

    private fun idle(number: String? = null) =
        tracker.onPhoneState(TelephonyManager.CALL_STATE_IDLE, number)

    private fun ringing(number: String?) =
        tracker.onPhoneState(TelephonyManager.CALL_STATE_RINGING, number)

    @Test
    fun offhookFromAnsweredVoipCallReportsNoCellularCall() {
        simCall = false
        // A self-managed WhatsApp call rings and is answered: RINGING, then OFFHOOK, no number.
        tracker.handleState(TelephonyManager.CALL_STATE_RINGING, numberFinal = true)
        tracker.handleState(TelephonyManager.CALL_STATE_OFFHOOK, numberFinal = true)
        assertTrue(sent.isEmpty())
        assertNull(tracker.currentCallState())
        tracker.handleState(TelephonyManager.CALL_STATE_IDLE, numberFinal = true)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun offhookOfAnOutgoingVoipCallReportsNothing() {
        // A WhatsApp call placed on the phone: OFFHOOK with no ring, no number and no
        // NEW_OUTGOING_CALL (Telecom sends neither for self-managed calls).
        simCall = false
        offhook()
        assertTrue(sent.isEmpty())
        runScheduled()
        assertTrue(sent.isEmpty())
        assertNull(tracker.currentCallState())
        // The duplicate number-less broadcast doesn't reopen the question.
        offhook("")
        runScheduled()
        idle()
        runScheduled()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun outgoingCallWithTheNumberFromNewOutgoingCall() {
        tracker.onNewOutgoingCall("+441234567890")
        offhook()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        val dialing = sent.single()
        assertEquals(CallDirection.CALL_DIRECTION_OUTGOING, dialing.direction)
        assertEquals(CallKind.CALL_KIND_CELLULAR, dialing.kind)
        assertEquals("+441234567890", dialing.number)
        assertEquals("Alice", dialing.contactName)
        assertEquals(now, dialing.startedAtMs)
        val startedAt = now

        runScheduled()
        assertEquals(1, sent.size)
        now += 5_000
        tracker.onDialerCallTimerShown()
        assertEquals(CallPhase.CALL_PHASE_ACTIVE, sent.last().phase)
        assertEquals(CallPhase.CALL_PHASE_ACTIVE, tracker.currentCallState()?.phase)
        now += 60_000
        idle()
        runScheduled()
        assertEquals(
            listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ACTIVE, CallPhase.CALL_PHASE_ENDED),
            phases(),
        )
        val ended = sent.last()
        assertEquals(startedAt, ended.startedAtMs)
        assertEquals(now, ended.endedAtMs)
        assertEquals("+441234567890", ended.number)
        assertEquals(1, sent.map { it.callId }.distinct().size)
        assertTrue(sent.all { it.direction == CallDirection.CALL_DIRECTION_OUTGOING })
        assertEquals(0, callLogQueries)
        assertNull(tracker.currentCallState())
    }

    @Test
    fun newOutgoingCallArrivingJustAfterOffhookStillCarriesTheNumber() {
        simCall = false // the gate would say no; the broadcast proves a SIM call
        offhook()
        assertTrue(sent.isEmpty())
        tracker.onNewOutgoingCall("+441234567890")
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("+441234567890", sent.single().number)
        runScheduled()
        assertEquals(1, sent.size)
    }

    @Test
    fun outgoingCallWithTheNumberInThePhoneStateBroadcast() {
        // With READ_CALL_LOG the OFFHOOK broadcast comes twice, the second with the number.
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        simCall = false
        offhook()
        offhook("+441234567890")
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("+441234567890", sent.single().number)
        assertEquals("Alice", sent.single().contactName)
    }

    @Test
    fun outgoingCallWithoutTheNumberIsFilledFromTheCallLogAtTheEnd() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        val startedAt = now
        offhook()
        // The OFFHOOK broadcast for READ_CALL_LOG holders has an empty number here.
        offhook("")
        assertTrue(sent.isEmpty())
        runScheduled()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("", sent.single().number)
        assertEquals(CallDirection.CALL_DIRECTION_OUTGOING, sent.single().direction)

        now += 30_000
        idle()
        assertEquals(1, sent.size) // waiting for the call log entry
        callLogEntry = OutgoingLogEntry("+441234567890", "", startedAt - 100)
        val endedAt = now
        runScheduled()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ENDED), phases())
        val ended = sent.last()
        assertEquals("+441234567890", ended.number)
        assertEquals("Alice", ended.contactName)
        assertEquals(startedAt, ended.startedAtMs)
        assertEquals(endedAt, ended.endedAtMs)
        assertEquals(sent.first().callId, ended.callId)
    }

    @Test
    fun outgoingCallWithoutTheNumberEndsAnywayWhenTheCallLogHasNoEntry() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        offhook()
        runScheduled()
        idle()
        runScheduled()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ENDED), phases())
        assertEquals("", sent.last().number)
        assertTrue(callLogQueries > 1)
    }

    @Test
    fun outgoingCallWithoutAnyCallPermissionShowsANumberlessCall() {
        offhook()
        runScheduled()
        idle()
        runScheduled()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ENDED), phases())
        assertTrue(sent.all { it.number.isEmpty() && it.direction == CallDirection.CALL_DIRECTION_OUTGOING })
        assertEquals(0, callLogQueries)
    }

    @Test
    fun outgoingCallCancelledBeforeItConnectsEnds() {
        tracker.onNewOutgoingCall("+441234567890")
        offhook()
        now += 2_000
        idle()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ENDED), phases())
    }

    @Test
    fun emergencyNumberIsShownLikeAnyOther() {
        tracker.onNewOutgoingCall("112")
        offhook("112")
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("112", sent.single().number)
    }

    @Test
    fun callWaitingDuringAnOutgoingCallKeepsTheOutgoingCall() {
        tracker.onNewOutgoingCall("+441234567890")
        offhook()
        tracker.onDialerCallTimerShown()
        ringing("+449999999999")
        val state = tracker.currentCallState()
        assertEquals(CallPhase.CALL_PHASE_ACTIVE, state?.phase)
        assertEquals("+441234567890", state?.number)
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING, CallPhase.CALL_PHASE_ACTIVE), phases())
        // The waiting call is declined: back to OFFHOOK for the same call.
        offhook()
        assertEquals("+441234567890", tracker.currentCallState()?.number)
        assertEquals(1, sent.map { it.callId }.distinct().size)
        // A further NEW_OUTGOING_CALL during the call doesn't take over either.
        tracker.onNewOutgoingCall("+447777777777")
        assertEquals("+441234567890", tracker.currentCallState()?.number)
    }

    @Test
    fun callWaitingWhileStillDialingStaysDialing() {
        tracker.onNewOutgoingCall("+441234567890")
        offhook()
        ringing("+449999999999")
        offhook()
        assertEquals(CallPhase.CALL_PHASE_DIALING, tracker.currentCallState()?.phase)
        assertTrue(phases().none { it == CallPhase.CALL_PHASE_RINGING })
    }

    @Test
    fun macDialPathIsUnchanged() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.CALL_PHONE)
        assertTrue(tracker.dial("+441234567890", null))
        // Telecom sends NEW_OUTGOING_CALL for the Mac's own dial too: no second call.
        tracker.onNewOutgoingCall("+441234567890")
        offhook()
        assertEquals(listOf(CallPhase.CALL_PHASE_ACTIVE), phases())
        assertEquals(CallDirection.CALL_DIRECTION_OUTGOING, sent.single().direction)
        assertEquals("+441234567890", sent.single().number)
        runScheduled()
        tracker.onDialerCallTimerShown()
        idle()
        assertEquals(listOf(CallPhase.CALL_PHASE_ACTIVE, CallPhase.CALL_PHASE_ENDED), phases())
        assertEquals(1, sent.map { it.callId }.distinct().size)
        assertEquals(0, callLogQueries)
    }

    @Test
    fun numberDialledOnThePhoneWhileAMacDialIsPendingWins() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.CALL_PHONE)
        assertTrue(tracker.dial("+441234567890", null))
        // The Mac's request didn't become a call; the user dials another number on the phone.
        tracker.onNewOutgoingCall("+447777777777")
        offhook()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("+447777777777", sent.single().number)
    }

    @Test
    fun staleMacDialDoesNotLabelALaterPhoneCall() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.CALL_PHONE)
        assertTrue(tracker.dial("+441234567890", null))
        now += 5 * 60_000
        offhook()
        runScheduled()
        assertEquals(listOf(CallPhase.CALL_PHASE_DIALING), phases())
        assertEquals("", sent.single().number)
    }

    @Test
    fun ringWronglyTakenForSimIsRetractedWhenOffhookIsNotASimCall() {
        tracker.handleState(TelephonyManager.CALL_STATE_RINGING, numberFinal = true)
        simCall = false
        tracker.handleState(TelephonyManager.CALL_STATE_OFFHOOK, numberFinal = true)
        assertEquals(listOf(CallPhase.CALL_PHASE_RINGING, CallPhase.CALL_PHASE_MISSED), phases())
        assertNull(tracker.currentCallState())
    }

    @Test
    fun realIncomingCellularCallIsStillReported() {
        tracker.handleState(TelephonyManager.CALL_STATE_RINGING, numberFinal = true)
        tracker.handleState(TelephonyManager.CALL_STATE_OFFHOOK, numberFinal = true)
        assertEquals(CallPhase.CALL_PHASE_ACTIVE, tracker.currentCallState()?.phase)
        tracker.handleState(TelephonyManager.CALL_STATE_IDLE, numberFinal = true)
        assertEquals(
            listOf(CallPhase.CALL_PHASE_RINGING, CallPhase.CALL_PHASE_ACTIVE, CallPhase.CALL_PHASE_ENDED),
            phases(),
        )
        assertTrue(sent.all { it.kind == CallKind.CALL_KIND_CELLULAR })
        assertEquals(1, sent.map { it.callId }.distinct().size)
    }

    @Test
    fun cellularCallWaitingDuringActiveCallKeepsTheActiveCall() {
        tracker.handleState(TelephonyManager.CALL_STATE_RINGING, numberFinal = true)
        tracker.handleState(TelephonyManager.CALL_STATE_OFFHOOK, numberFinal = true)
        tracker.handleState(TelephonyManager.CALL_STATE_RINGING, numberFinal = true)
        assertEquals(CallPhase.CALL_PHASE_ACTIVE, tracker.currentCallState()?.phase)
    }
}
