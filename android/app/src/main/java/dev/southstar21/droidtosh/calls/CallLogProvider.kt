package dev.southstar21.droidtosh.calls

import android.content.Context
import android.provider.CallLog
import dev.southstar21.droidtosh.protocol.v1.CallLogPage

class CallLogProvider(private val context: Context) {
    private val contacts by lazy { loadContactNames() }
    private val accounts by lazy { PhoneAccountsProvider(context).labels() }

    fun page(offset: Int, limit: Int): CallLogPage = runCatching {
        val page = CallLogPage.newBuilder()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        val queryUri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .appendQueryParameter(CallLog.Calls.OFFSET_PARAM_KEY, offset.toString())
            .build()
        context.contentResolver.query(
            queryUri,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                CallLogMapper.toEntry(cursor, accounts, contacts)?.let { page.addEntries(it) }
            }
        }
        page.setHasMore(page.entriesCount == limit)
        page.build()
    }.getOrElse { CallLogPage.getDefaultInstance() }

    private fun loadContactNames(): Map<String, String> = runCatching {
        val names = mutableMapOf<String, String>()
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                names[CallLogMapper.normalizeNumber(number)] = name
            }
        }
        names
    }.getOrDefault(emptyMap())
}
