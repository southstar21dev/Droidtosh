package dev.southstar21.droidtosh.notifications

class NotificationFilter(
    private val mutedPackages: () -> Set<String>,
    private val ownPackage: String,
) {
    fun shouldForward(packageName: String, isGroupSummary: Boolean): Boolean {
        if (packageName == ownPackage) return false
        if (isSystemNoise(packageName)) return false
        if (isGroupSummary) return false
        if (packageName in mutedPackages()) return false
        return true
    }

    companion object {
        private val systemNoisePackages = setOf(
            "com.android.systemui",
            "com.samsung.android.app.routines",
        )

        fun isSystemNoise(packageName: String): Boolean = packageName in systemNoisePackages
    }
}
