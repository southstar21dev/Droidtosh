package dev.southstar21.droidtosh.notifications

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.southstar21.droidtosh.protocol.v1.AppInfo

object AppListMapper {
    fun toAppInfos(
        apps: List<ApplicationInfo>,
        labelOf: (ApplicationInfo) -> String,
        isLaunchable: (ApplicationInfo) -> Boolean,
    ): List<AppInfo> =
        apps.asSequence()
            .filter(isLaunchable)
            .map { app ->
                AppInfo.newBuilder()
                    .setPackageName(app.packageName)
                    .setLabel(labelOf(app))
                    .build()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
}

class InstalledAppsProvider(private val packageManager: PackageManager) {
    fun launchableApps(): List<AppInfo> =
        AppListMapper.toAppInfos(
            // Only launcher apps are visible (see <queries> in the manifest), so ask for those.
            apps = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).map { it.activityInfo.applicationInfo },
            labelOf = { it.loadLabel(packageManager).toString() },
            isLaunchable = { packageManager.getLaunchIntentForPackage(it.packageName) != null },
        )
}
