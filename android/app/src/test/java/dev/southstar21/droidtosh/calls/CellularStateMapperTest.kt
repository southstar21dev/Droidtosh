package dev.southstar21.droidtosh.calls

import android.telephony.TelephonyManager
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class CellularStateMapperTest {
    @Test
    fun ringingMapsToRinging() {
        assertEquals(
            CallPhase.CALL_PHASE_RINGING,
            CellularStateMapper.phaseFor(TelephonyManager.CALL_STATE_RINGING, wasOffhook = false)
        )
    }

    @Test
    fun offhookMapsToActive() {
        assertEquals(
            CallPhase.CALL_PHASE_ACTIVE,
            CellularStateMapper.phaseFor(TelephonyManager.CALL_STATE_OFFHOOK, wasOffhook = false)
        )
    }

    @Test
    fun idleAfterOffhookMapsToEnded() {
        assertEquals(
            CallPhase.CALL_PHASE_ENDED,
            CellularStateMapper.phaseFor(TelephonyManager.CALL_STATE_IDLE, wasOffhook = true)
        )
    }

    @Test
    fun idleWithoutOffhookMapsToMissed() {
        assertEquals(
            CallPhase.CALL_PHASE_MISSED,
            CellularStateMapper.phaseFor(TelephonyManager.CALL_STATE_IDLE, wasOffhook = false)
        )
    }
}
