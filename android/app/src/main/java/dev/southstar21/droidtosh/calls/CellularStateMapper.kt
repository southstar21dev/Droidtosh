package dev.southstar21.droidtosh.calls

import android.telephony.TelephonyManager
import dev.southstar21.droidtosh.protocol.v1.CallPhase

object CellularStateMapper {
    fun phaseFor(state: Int, wasOffhook: Boolean): CallPhase? = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> CallPhase.CALL_PHASE_RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallPhase.CALL_PHASE_ACTIVE
        TelephonyManager.CALL_STATE_IDLE ->
            if (wasOffhook) CallPhase.CALL_PHASE_ENDED else CallPhase.CALL_PHASE_MISSED
        else -> null
    }
}
