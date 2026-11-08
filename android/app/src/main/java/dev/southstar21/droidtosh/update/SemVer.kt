package dev.southstar21.droidtosh.update

/**
 * A semantic version (semver.org, 2.0.0), ordered by its precedence rules: major, minor and patch
 * compare numerically; a pre-release (`1.0.0-beta.2`) comes before its release; pre-release
 * identifiers compare one by one, numbers numerically and below text; and build metadata
 * (`+268.c5b81ac`) is ignored.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        }
        for ((mine, theirs) in preRelease.zip(other.preRelease)) {
            val cmp = compareIdentifiers(mine, theirs)
            if (cmp != 0) return cmp
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else preRelease.joinToString(".", prefix = "-")

    companion object {
        private val PATTERN = Regex(
            """^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        /** Parses `X.Y.Z[-pre][+build]`, with an optional leading `v`; null when it isn't one. */
        fun parse(text: String): SemVer? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            val identifiers = if (pre.isEmpty()) emptyList() else pre.split('.')
            // A numeric identifier must not have a leading zero.
            if (identifiers.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return SemVer(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                identifiers,
            )
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumber = a.toLongOrNull()?.takeIf { a.all(Char::isDigit) }
            val bNumber = b.toLongOrNull()?.takeIf { b.all(Char::isDigit) }
            return when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
