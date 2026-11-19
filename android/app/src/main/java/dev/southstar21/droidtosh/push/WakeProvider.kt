package dev.southstar21.droidtosh.push

import java.net.URI
import java.net.URISyntaxException

/** How the relay wakes the phone when the Mac comes back (protocol-v2, push tokens). */
enum class WakeProvider { FCM, UNIFIEDPUSH, NONE }

/** The pure rules for picking a provider and building the join line's token field. */
object WakeToken {
    /** The relay's cap on the whole token field; "up:" plus the endpoint must fit (relay/protocol.go). */
    const val MAX_TOKEN_FIELD = 400
    private const val PREFIX = "up:"

    /**
     * The token field for a UnifiedPush endpoint, or null when the relay would refuse it. The relay
     * rejects the whole join line over a bad endpoint (relay/unifiedpush.go), so this is at least as
     * strict: printable ASCII only (so length is the byte count), an https URL with a host, no
     * userinfo and no fragment.
     */
    fun unifiedPush(endpoint: String?): String? {
        val url = endpoint?.trim().orEmpty()
        if (!url.startsWith("https://") || url.any { it <= ' ' || it >= '\u007f' }) return null
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        val field = PREFIX + url
        return field.takeIf { it.length <= MAX_TOKEN_FIELD }
    }

    /**
     * The provider in use. An explicit [chosen] None or UnifiedPush wins; otherwise FCM where the
     * build has it, then a registered UnifiedPush endpoint, then nothing.
     */
    fun effective(
        chosen: WakeProvider?,
        fcmAvailable: Boolean,
        hasEndpoint: Boolean,
    ): WakeProvider = when {
        chosen == WakeProvider.NONE -> WakeProvider.NONE
        chosen == WakeProvider.UNIFIEDPUSH -> WakeProvider.UNIFIEDPUSH
        fcmAvailable -> WakeProvider.FCM
        hasEndpoint -> WakeProvider.UNIFIEDPUSH
        else -> WakeProvider.NONE
    }
}
