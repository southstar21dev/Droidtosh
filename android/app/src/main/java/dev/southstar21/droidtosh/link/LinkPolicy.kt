package dev.southstar21.droidtosh.link

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
        // By code point, so a supplementary-plane format or private-use character is caught too.
        if (raw.codePoints().anyMatch(::isForbidden)) return null
        if (raw.startsWith("tel:", ignoreCase = true)) return if (allowTel) tel(raw) else null
        return web(raw)
    }

    /** [text] as a link when, ignoring surrounding whitespace, it is exactly one http(s) URL. */
    fun singleWebUrl(text: String): String? {
        val trimmed = text.trim()
        return (validate(trimmed, allowTel = false) as? Web)?.url
    }

    private fun isForbidden(c: Int): Boolean {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return true
        val type = Character.getType(c)
        // Format characters include bidi overrides and zero-width characters, which disguise a host.
        return type == Character.FORMAT.toInt() ||
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.UNASSIGNED.toInt() ||
            type == Character.SURROGATE.toInt()
    }

    private fun web(raw: String): Link? {
        val separator = raw.indexOf("://")
        if (separator < 0) return null
        val scheme = raw.substring(0, separator)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
        val rest = raw.substring(separator + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val host = parseHost(authority) ?: return null
        return Web(raw, host)
    }

    /**
     * The host of an authority under the one rule both platforms share (PROTOCOL-v2.md section 10),
     * or null. No userinfo; a bracketed IPv6 literal or dot-separated non-empty labels (one trailing
     * dot allowed) of ASCII letters, digits, `-`, `_` and non-ASCII letters, marks and digits; an
     * optional `:port` of 1 to 5 digits in 1..65535. Lowercased for ASCII only.
     */
    internal fun parseHost(authority: String): String? {
        if (authority.isEmpty() || authority.contains('@')) return null
        val host: String
        val port: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            port = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return null
            }
            val literal = host.substring(1, host.length - 1)
            if (literal.isEmpty() || !literal.contains(':')) return null
            if (!literal.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        } else {
            val colon = authority.indexOf(':')
            host = if (colon < 0) authority else authority.substring(0, colon)
            port = if (colon < 0) null else authority.substring(colon + 1)
            val labels = host.removeSuffix(".").split('.')
            if (labels.any { label -> label.isEmpty() || !label.codePoints().allMatch(::isHostChar) }) return null
        }
        if (port != null) {
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
            if (port.toInt() !in 1..65535) return null
        }
        return buildString(host.length) { host.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }
    }

    private fun isHostChar(c: Int): Boolean {
        if (c < 0x80) return c.toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        return when (Character.getType(c).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.DECIMAL_DIGIT_NUMBER -> true
            else -> false
        }
    }

    private fun tel(raw: String): Link? {
        val number = raw.substring("tel:".length)
        if (number.isEmpty() || number.length > MAX_TEL_LENGTH) return null
        if (!number.all { it in '0'..'9' || it in "+*#().-" }) return null
        if (number.none { it in '0'..'9' }) return null
        return Tel("tel:$number", number)
    }
}
