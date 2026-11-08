package dev.southstar21.droidtosh.notifications

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledAppsProviderTest {
    private fun app(packageName: String): ApplicationInfo =
        ApplicationInfo().apply { this.packageName = packageName }

    @Test
    fun filtersNonLaunchableAndSortsByLabel() {
        val apps = listOf(app("com.b"), app("skip.me"), app("com.a"))
        val result = AppListMapper.toAppInfos(
            apps = apps,
            labelOf = { it.packageName.removePrefix("com.") },
            isLaunchable = { it.packageName != "skip.me" },
        )
        assertEquals(listOf("a", "b"), result.map { it.label })
        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }
}
