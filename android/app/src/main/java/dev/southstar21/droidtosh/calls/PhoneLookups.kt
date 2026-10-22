package dev.southstar21.droidtosh.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract

/** The call log's entry for a call dialled on the phone, read once the call has ended. */
data class OutgoingLogEntry(
    val number: String,
    val phoneAccountId: String,
    val startedAtMs: Long,
)

/** Contact and call log lookups for a single cellular call. */
object PhoneLookups {
    /** The contact's display name for [number], or "" without READ_CONTACTS or a match. */
    fun contactName(context: Context, number: String): String {
        if (number.isBlank()) return ""
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return ""
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
        }.getOrDefault("")
    }

    /**
     * The newest outgoing call log entry created at or after [sinceMs], or null without
     * READ_CALL_LOG or before the dialer has written it (it does so just after the call ends).
     */
    fun latestOutgoing(context: Context, sinceMs: Long): OutgoingLogEntry? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1")
                    .build(),
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceMs.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                OutgoingLogEntry(
                    number = cursor.getString(0).orEmpty(),
                    phoneAccountId = cursor.getString(1).orEmpty(),
                    startedAtMs = cursor.getLong(2),
                )
            }
        }.getOrNull()
    }
}
