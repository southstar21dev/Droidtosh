package dev.southstar21.droidtosh.calls

import android.media.AudioManager

/**
 * Decides whether a phone-state broadcast belongs to a real SIM call.
 *
 * `ACTION_PHONE_STATE_CHANGED` reflects every Telecom call, including self-managed VoIP calls
 * such as WhatsApp's: when one is answered the state goes OFFHOOK just as for a SIM call, but
 * with no number. Reporting that as a cellular call replaced the VoIP call on the Mac with an
 * "Unknown caller · Cellular" one.
 */
object CellularCallGate {
    /**
     * @param inManagedCall `TelecomManager.isInManagedCall()`, which is true only for SIM (managed)
     *   calls, or null when it can't be read (no READ_PHONE_STATE).
     * @param audioMode `AudioManager.getMode()`: SIM calls use MODE_IN_CALL, VoIP calls
     *   MODE_IN_COMMUNICATION.
     * @param voipCallInProgress a VoIP call notification is showing.
     * @param ownDial this app placed the call (a dial request from the Mac).
     */
    fun isSimCall(
        inManagedCall: Boolean?,
        audioMode: Int,
        voipCallInProgress: Boolean,
        ownDial: Boolean,
    ): Boolean {
        if (inManagedCall != null) return inManagedCall
        if (ownDial) return true
        if (audioMode == AudioManager.MODE_IN_CALL) return true
        if (audioMode == AudioManager.MODE_IN_COMMUNICATION) return false
        return !voipCallInProgress
    }
}
