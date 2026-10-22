package dev.southstar21.droidtosh.calls

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellularCallGateTest {
    @Test
    fun offhookFromSelfManagedVoipCallIsNotASimCall() {
        // WhatsApp answered: Telecom reports OFFHOOK, but no managed (SIM) call exists.
        assertFalse(
            CellularCallGate.isSimCall(
                inManagedCall = false,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                voipCallInProgress = true,
                ownDial = false,
            )
        )
    }

    @Test
    fun managedCallIsASimCallEvenDuringAVoipCall() {
        // Call waiting: a SIM call rings while a VoIP call is in progress.
        assertTrue(
            CellularCallGate.isSimCall(
                inManagedCall = true,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                voipCallInProgress = true,
                ownDial = false,
            )
        )
    }

    @Test
    fun withoutPhoneStatePermissionInCommunicationModeIsNotASimCall() {
        assertFalse(
            CellularCallGate.isSimCall(
                inManagedCall = null,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                voipCallInProgress = false,
                ownDial = false,
            )
        )
    }

    @Test
    fun withoutPhoneStatePermissionInCallModeIsASimCall() {
        assertTrue(
            CellularCallGate.isSimCall(
                inManagedCall = null,
                audioMode = AudioManager.MODE_IN_CALL,
                voipCallInProgress = true,
                ownDial = false,
            )
        )
    }

    @Test
    fun withoutPhoneStatePermissionAVoipNotificationRulesOutASimCall() {
        assertFalse(
            CellularCallGate.isSimCall(
                inManagedCall = null,
                audioMode = AudioManager.MODE_RINGTONE,
                voipCallInProgress = true,
                ownDial = false,
            )
        )
        assertTrue(
            CellularCallGate.isSimCall(
                inManagedCall = null,
                audioMode = AudioManager.MODE_RINGTONE,
                voipCallInProgress = false,
                ownDial = false,
            )
        )
    }

    @Test
    fun ownDialIsASimCallWhenTelecomCannotBeAsked() {
        assertTrue(
            CellularCallGate.isSimCall(
                inManagedCall = null,
                audioMode = AudioManager.MODE_NORMAL,
                voipCallInProgress = true,
                ownDial = true,
            )
        )
    }
}
