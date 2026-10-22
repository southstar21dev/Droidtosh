package dev.southstar21.droidtosh.calls

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.CallActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellularCallActionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduled = mutableListOf<() -> Unit>()
    private val dialerTaps = mutableListOf<VoipAction>()
    private var dialerHasAction = true

    private class FakeControl(var accepts: Boolean = true, var ends: Boolean = true) : CellularCallControl {
        var acceptCalls = 0
        var endCalls = 0
        override fun acceptRingingCall(): Boolean {
            acceptCalls++
            return accepts
        }
        override fun endCall(): Boolean {
            endCalls++
            return ends
        }
    }

    private val control = FakeControl()
    private val tracker = CellularCallTracker(
        context,
        simCallCheck = { true },
        sendState = {},
        postDelayed = { _, block -> scheduled.add(block) },
        contactLookup = { "" },
        outgoingLogLookup = { null },
        callControl = control,
        dialerAction = { role ->
            dialerTaps.add(role)
            dialerHasAction
        },
    )

    private fun ringingCallId(): String {
        tracker.onPhoneState(TelephonyManager.CALL_STATE_RINGING, "+441234567890")
        return tracker.currentCallState()!!.callId
    }

    private fun runScheduled() {
        while (scheduled.isNotEmpty()) scheduled.removeAt(0).invoke()
    }

    @Test
    fun acceptUsesTelecomBeforeTheDialerNotification() {
        val id = ringingCallId()
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_ACCEPT))
        assertEquals(1, control.acceptCalls)
        // The call answers: no dialer notification tap, even after the check.
        tracker.onPhoneState(TelephonyManager.CALL_STATE_OFFHOOK, "+441234567890")
        runScheduled()
        assertTrue(dialerTaps.isEmpty())
    }

    @Test
    fun acceptFallsBackToTheDialerWithoutTelecom() {
        // No ANSWER_PHONE_CALLS, or acceptRingingCall threw.
        control.accepts = false
        val id = ringingCallId()
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_ACCEPT))
        assertEquals(listOf(VoipAction.ACCEPT), dialerTaps)

        dialerHasAction = false
        assertFalse(tracker.handleAction(id, CallActionType.CALL_ACTION_ACCEPT))
    }

    @Test
    fun acceptThatLeavesTheCallRingingTriesTheDialer() {
        // Some OEMs ignore acceptRingingCall for apps that aren't the default dialer.
        val id = ringingCallId()
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_ACCEPT))
        assertTrue(dialerTaps.isEmpty())
        runScheduled()
        assertEquals(listOf(VoipAction.ACCEPT), dialerTaps)
    }

    @Test
    fun acceptCheckIgnoresACallThatEndedMeanwhile() {
        val id = ringingCallId()
        tracker.handleAction(id, CallActionType.CALL_ACTION_ACCEPT)
        tracker.onPhoneState(TelephonyManager.CALL_STATE_IDLE, null)
        runScheduled()
        assertTrue(dialerTaps.isEmpty())
    }

    @Test
    fun declineAndEndUseTelecomFirst() {
        val id = ringingCallId()
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_DECLINE))
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_END))
        assertEquals(2, control.endCalls)
        assertTrue(dialerTaps.isEmpty())
    }

    @Test
    fun declineAndEndFallBackToTheDialer() {
        control.ends = false
        val id = ringingCallId()
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_DECLINE))
        assertTrue(tracker.handleAction(id, CallActionType.CALL_ACTION_END))
        assertEquals(listOf(VoipAction.DECLINE, VoipAction.END), dialerTaps)
    }

    @Test
    fun actionsForOtherCallsAreRefused() {
        assertFalse(tracker.handleAction("voip:x", CallActionType.CALL_ACTION_ACCEPT))
        assertEquals(0, control.acceptCalls)
    }
}
