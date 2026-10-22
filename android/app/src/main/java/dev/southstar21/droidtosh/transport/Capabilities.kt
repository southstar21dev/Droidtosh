package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Envelope.PayloadCase

/** Capability strings negotiated in the v2 handshake (protocol/PROTOCOL-v2.md §3). */
object Capabilities {
    const val NOTIFICATIONS = "notifications"
    const val CALLS = "calls"
    const val CALLLOG = "calllog"
    const val CLIPBOARD_TEXT = "clipboard.text"
    const val CLIPBOARD_IMAGE = "clipboard.image"
    const val FILES = "files"
    const val PHONE_STATUS = "phone.status"
    const val FIND = "find"
    const val RELIABLE = "reliable"

    /** Everything this build supports, advertised in AuthResponse. */
    val SUPPORTED: List<String> =
        listOf(NOTIFICATIONS, CALLS, CALLLOG, CLIPBOARD_TEXT, CLIPBOARD_IMAGE, FILES, PHONE_STATUS, FIND, RELIABLE)

    /** The capability an outgoing feature envelope needs, or null if it is always allowed. */
    fun requiredFor(envelope: Envelope): String? = when (envelope.payloadCase) {
        PayloadCase.NOTIFICATION_POSTED,
        PayloadCase.NOTIFICATION_UPDATED,
        PayloadCase.NOTIFICATION_REMOVED,
        PayloadCase.SNAPSHOT_REPLY,
        PayloadCase.APPS_LIST,
        PayloadCase.APP_ICON,
        PayloadCase.RELAY_STATUS -> NOTIFICATIONS
        PayloadCase.CALL_STATE -> CALLS
        PayloadCase.CALLLOG_PAGE -> CALLLOG
        PayloadCase.CLIPBOARD_TEXT -> CLIPBOARD_TEXT
        PayloadCase.CLIPBOARD_BLOB_START,
        PayloadCase.CLIPBOARD_BLOB_CHUNK,
        PayloadCase.CLIPBOARD_BLOB_END -> CLIPBOARD_IMAGE
        PayloadCase.FILE_OFFER,
        PayloadCase.FILE_ACCEPT,
        PayloadCase.FILE_REJECT,
        PayloadCase.FILE_CHUNK,
        PayloadCase.FILE_PROGRESS,
        PayloadCase.FILE_END,
        PayloadCase.FILE_COMPLETE,
        PayloadCase.FILE_CANCEL -> FILES
        PayloadCase.DEVICE_STATUS -> PHONE_STATUS
        PayloadCase.FIND_PHONE_STATE -> FIND
        else -> null
    }
}
