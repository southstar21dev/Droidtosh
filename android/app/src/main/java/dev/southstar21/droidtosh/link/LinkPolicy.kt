package dev.southstar21.droidtosh.link

import java.net.URI

/**
 * What an `OpenUrl` may carry (protocol/PROTOCOL-v2.md section 10), checked by the receiver and
 * before sending. Strict on purpose: a link is only ever opened by a user tap, but the notification
 * still must not show or open anything surprising.
 */
object LinkPolicy {
    const val MAX_BYTES = 2048
    private const val MAX_TEL_LENGTH = 32

    sealed interface Link {
        val url: String

        /** What the notification shows after "Open link from Mac:". */
        val display: String
    }

    data class Web(override val url: String, val host: String) : Link {
        override val display: String get() = host
    }

    data class Tel(override val url: String, val number: String) : Link {
        override val display: String get() = number
    }

    /** The link if [raw] is acceptable, else null. [allowTel] admits `tel:` numbers (phone side only). */
    fun validate(raw: String, allowTel: Boolean): Link? {
        if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        if (raw.any(::isForbidden)) return null
        if (raw.startsWith("tel:", ignoreCase = true)) return if (allowTel) tel(raw) else null
        return web(raw)
    }

    /** [text] as a link when, ignoring surrounding whitespace, it is exactly one http(s) URL. */
    fun singleWebUrl(text: String): String? {
        val trimmed = text.trim()
        return (validate(trimmed, allowTel = false) as? Web)?.url
    }

    private fun isForbidden(c: Char): Boolean {
        if (c.isWhitespace() || Character.isISOControl(c)) return true
        val type = Character.getType(c)
        // Format characters include bidi overrides and zero-width characters, which disguise a host.
        return type == Character.FORMAT.toInt() ||
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.UNASSIGNED.toInt()
    }

    private fun web(raw: String): Link? {
        val scheme = raw.substringBefore("://", missingDelimiterValue = "")
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return null
        }
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return Web(raw, host)
    }

    private fun tel(raw: String): Link? {
        val number = raw.substring("tel:".length)
        if (number.isEmpty() || number.length > MAX_TEL_LENGTH) return null
        if (!number.all { it in '0'..'9' || it in "+*#().-" }) return null
        if (number.none { it in '0'..'9' }) return null
        return Tel("tel:$number", number)
    }
}
