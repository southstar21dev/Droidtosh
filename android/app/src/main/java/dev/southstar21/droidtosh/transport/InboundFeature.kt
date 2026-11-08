package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Envelope.PayloadCase

/** The phone-side feature that handles an inbound envelope; each has its own inbox in [TransportHub]. */
enum class InboundFeature(val capacity: Int) {
    NOTIFICATIONS(128),
    CALLS(64),
    CLIPBOARD(192),
    // The sender keeps at most 4 MiB (16 chunks) unacknowledged and the receiver only acknowledges
    // what it has written, so this inbox never holds more than a window plus a few control messages.
    FILES(64),
    // Find my phone requests: a few at most, and only the latest matters.
    DEVICE(16),
    ;

    companion object {
        private const val CELLULAR_PREFIX = "cellular:"

        fun of(envelope: Envelope): InboundFeature? = when (envelope.payloadCase) {
            PayloadCase.NOTIFICATION_ACTION,
            PayloadCase.NOTIFICATION_DISMISS,
            PayloadCase.SNAPSHOT_REQUEST,
            PayloadCase.MUTE_LIST_SET,
            PayloadCase.APPS_LIST_REQUEST,
            PayloadCase.APP_ICON,
            PayloadCase.OPEN_APP_REQUEST -> NOTIFICATIONS
            // VoIP calls are driven by the notification listener; only cellular calls belong to CALLS.
            PayloadCase.CALL_ACTION ->
                if (envelope.callAction.callId.startsWith(CELLULAR_PREFIX)) CALLS else NOTIFICATIONS
            PayloadCase.DIAL_REQUEST,
            PayloadCase.CALLLOG_REQUEST -> CALLS
            PayloadCase.CLIPBOARD_TEXT,
            PayloadCase.CLIPBOARD_BLOB_START,
            PayloadCase.CLIPBOARD_BLOB_CHUNK,
            PayloadCase.CLIPBOARD_BLOB_END -> CLIPBOARD
            PayloadCase.FILE_OFFER,
            PayloadCase.FILE_ACCEPT,
            PayloadCase.FILE_REJECT,
            PayloadCase.FILE_CHUNK,
            PayloadCase.FILE_PROGRESS,
            PayloadCase.FILE_END,
            PayloadCase.FILE_COMPLETE,
            PayloadCase.FILE_CANCEL -> FILES
            PayloadCase.FIND_PHONE_REQUEST -> DEVICE
            else -> null
        }
    }
}
