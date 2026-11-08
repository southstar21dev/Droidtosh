package dev.southstar21.droidtosh.calls

import android.database.MatrixCursor
import android.provider.CallLog
import dev.southstar21.droidtosh.protocol.v1.CallLogType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallLogMapperTest {
    private fun cursor(): MatrixCursor = MatrixCursor(
        arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
    ).apply {
        addRow(arrayOf("1", "+15551234567", CallLog.Calls.INCOMING_TYPE, 1_730_000_000_000L, 42L, "sim-1"))
        addRow(arrayOf("2", "+15550000000", CallLog.Calls.MISSED_TYPE, 1_730_000_100_000L, 0L, null))
    }

    @Test
    fun mapsRowsWithNamesAndSimLabels() {
        val cursor = cursor()
        cursor.moveToFirst()
        val first = CallLogMapper.toEntry(cursor, mapOf("sim-1" to "Personal"), mapOf("+15551234567" to "Bob"))
        assertEquals("+15551234567", first!!.number)
        assertEquals("Bob", first.contactName)
        assertEquals(CallLogType.CALL_LOG_TYPE_INCOMING, first.type)
        assertEquals(42, first.durationS)
        assertEquals("Personal", first.simLabel)

        cursor.moveToNext()
        val second = CallLogMapper.toEntry(cursor, emptyMap(), emptyMap())
        assertEquals(CallLogType.CALL_LOG_TYPE_MISSED, second!!.type)
        assertEquals("", second.simLabel)
    }

    @Test
    fun mapsTypes() {
        assertEquals(CallLogType.CALL_LOG_TYPE_INCOMING, CallLogMapper.typeFor(CallLog.Calls.INCOMING_TYPE))
        assertEquals(CallLogType.CALL_LOG_TYPE_OUTGOING, CallLogMapper.typeFor(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(CallLogType.CALL_LOG_TYPE_MISSED, CallLogMapper.typeFor(CallLog.Calls.MISSED_TYPE))
        assertEquals(CallLogType.CALL_LOG_TYPE_REJECTED, CallLogMapper.typeFor(CallLog.Calls.REJECTED_TYPE))
    }
}
