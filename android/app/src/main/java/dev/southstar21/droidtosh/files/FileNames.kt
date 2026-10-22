package dev.southstar21.droidtosh.files

import java.text.Normalizer

/**
 * Turns a peer-supplied file name into something safe to save under (protocol/PROTOCOL-v2.md §7.3).
 * The Mac applies the same rules, so both sides agree on the saved name.
 */
object FileNames {
    const val MAX_BYTES = 200
    const val FALLBACK = "file"
    private const val MAX_EXTENSION_BYTES = 20

    /**
     * Normalizes to NFC (so a decomposed name is capped and compared like the Mac does), removes
     * path separators (`/ \ :`), control characters, line/paragraph separators and bidi
     * controls; trims whitespace, strips all leading dots and trims again; caps the result at 200
     * UTF-8 bytes on character boundaries, keeping a final extension of up to 20 bytes.
     */
    fun sanitize(name: String): String {
        val cleaned = buildString {
            for (c in Normalizer.normalize(name, Normalizer.Form.NFC)) if (!isStripped(c)) append(c)
        }
            .trim()
            .trimStart('.')
            .trim()
        if (cleaned.isEmpty()) return FALLBACK
        return truncate(cleaned).ifEmpty { FALLBACK }
    }

    private fun isStripped(c: Char): Boolean {
        val code = c.code
        return c == '/' || c == '\\' || c == ':' ||
            code < 0x20 || code in 0x7F..0x9F ||
            code == 0x2028 || code == 0x2029 ||
            code == 0x200E || code == 0x200F ||
            code in 0x202A..0x202E || code in 0x2066..0x2069
    }

    /** "a.txt" → "a (1).txt", "a (2).txt", … until [exists] is false. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, extension) = split(name)
        var n = 1
        while (true) {
            val candidate = "$base ($n)$extension"
            if (!exists(candidate)) return candidate
            n += 1
        }
    }

    /** Splits off the last extension including its dot; a leading dot is not an extension. */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private fun truncate(name: String): String {
        if (utf8Length(name) <= MAX_BYTES) return name
        val (base, extension) = split(name)
        val keep = if (base.isNotEmpty() && utf8Length(extension) <= MAX_EXTENSION_BYTES) extension else ""
        val source = if (keep.isEmpty()) name else base
        return takeUtf8(source, MAX_BYTES - utf8Length(keep)) + keep
    }

    private fun takeUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = utf8Length(codePoint)
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    private fun utf8Length(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            total += utf8Length(codePoint)
            i += Character.charCount(codePoint)
        }
        return total
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
