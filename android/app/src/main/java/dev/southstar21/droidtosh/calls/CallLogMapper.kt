package dev.southstar21.droidtosh.calls

import android.database.Cursor
import android.provider.CallLog
import dev.southstar21.droidtosh.protocol.v1.CallLogEntry
import dev.southstar21.droidtosh.protocol.v1.CallLogType

object CallLogMapper {
    fun typeFor(callType: Int): CallLogType = when (callType) {
        CallLog.Calls.OUTGOING_TYPE -> CallLogType.CALL_LOG_TYPE_OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallLogType.CALL_LOG_TYPE_MISSED
        CallLog.Calls.REJECTED_TYPE -> CallLogType.CALL_LOG_TYPE_REJECTED
        else -> CallLogType.CALL_LOG_TYPE_INCOMING
    }

    fun normalizeNumber(number: String): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun toEntry(
        cursor: Cursor,
        accountLabels: Map<String, String>,
        contactNames: Map<String, String>,
    ): CallLogEntry? {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID))
        val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)).orEmpty()
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
        val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
        val accountIdIndex = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
        val accountId = if (accountIdIndex >= 0) cursor.getString(accountIdIndex).orEmpty() else ""
        return CallLogEntry.newBuilder()
            .setId(id.toString())
            .setNumber(number)
            .setContactName((contactNames[number] ?: contactNames[normalizeNumber(number)]).orEmpty())
            .setType(typeFor(type))
            .setStartedAtMs(date)
            .setDurationS(duration.toInt())
            .setSimLabel(accountLabels[accountId].orEmpty())
            .setPhoneAccountId(accountId)
            .build()
    }
}
