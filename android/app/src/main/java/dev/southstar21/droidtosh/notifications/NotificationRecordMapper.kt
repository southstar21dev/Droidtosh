package dev.southstar21.droidtosh.notifications

import android.app.Notification
import dev.southstar21.droidtosh.protocol.v1.ActionInfo
import dev.southstar21.droidtosh.protocol.v1.NotificationRecord
import java.util.concurrent.ConcurrentHashMap

object NotificationRecordMapper {
    fun toRecord(
        packageName: String,
        appLabel: String,
        key: String,
        notification: Notification,
        postTimeMs: Long,
        /** Called once when any text field of this record was clipped to [MAX_TEXT_CHARS]. */
        onTruncated: () -> Unit = {},
    ): NotificationRecord {
        val extras = notification.extras
        var truncated = false
        fun field(key: String): String {
            val text = extras.getCharSequence(key)?.toString().orEmpty()
            if (text.length > MAX_TEXT_CHARS) truncated = true
            return clip(text, MAX_TEXT_CHARS)
        }
        val actions = notification.actions.orEmpty().mapIndexed { index, action ->
            ActionInfo.newBuilder()
                .setIndex(index)
                .setTitle(action.title?.toString().orEmpty())
                .setHasRemoteInput(action.remoteInputs?.isNotEmpty() == true)
                .build()
        }
        val remoteInputHint = notification.actions.orEmpty()
            .firstNotNullOfOrNull { action -> action.remoteInputs?.firstOrNull()?.label?.toString() }
            .orEmpty()
        val record = NotificationRecord.newBuilder()
            .setKey(key)
            .setPackageName(packageName)
            .setAppLabel(appLabel)
            .setTitle(field(Notification.EXTRA_TITLE))
            .setText(field(Notification.EXTRA_TEXT))
            .setBigText(field(Notification.EXTRA_BIG_TEXT))
            .setSubText(field(Notification.EXTRA_SUB_TEXT))
            .setCategory(notification.category.orEmpty())
            .setIsOngoing(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .setIsGroupSummary(notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .setPostedAtMs(postTimeMs)
            .addAllActions(actions)
            .setRemoteInputHint(remoteInputHint)
            .build()
        if (truncated) onTruncated()
        return record
    }

    /** Longest text field sent; keeps a single record well under the 1 MiB frame limit. */
    const val MAX_TEXT_CHARS = 4_000

    internal fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        // Don't split a surrogate pair at the cut.
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end) + "…"
    }
}

/**
 * Counts each notification whose text was truncated once, however often it's mapped again: a
 * BigTextStyle notification clips both its text and big text, and every update and snapshot
 * re-maps the same notification. A notification counts again after it's removed and reposted.
 */
class TruncationCounter(private val onCount: () -> Unit) {
    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun onTruncated(key: String) {
        if (keys.add(key)) onCount()
    }

    fun onRemoved(key: String) {
        keys.remove(key)
    }
}
