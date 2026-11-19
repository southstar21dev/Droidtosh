package dev.southstar21.droidtosh.background

/**
 * Manufacturers whose own battery manager kills background apps beyond what Android does
 * (see dontkillmyapp.com). [components] are the known settings screens as package to class pairs,
 * tried in order; which one exists depends on the model and ROM version, so the caller resolves
 * them at run time and falls back to [dontKillMyAppUrl].
 */
enum class OemVendor(
    val label: String,
    private val manufacturers: Set<String>,
    val components: List<Pair<String, String>>,
    private val dontKillMyAppSlug: String,
) {
    Xiaomi(
        "Xiaomi",
        setOf("xiaomi", "redmi", "poco"),
        listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        ),
        "xiaomi",
    ),
    Samsung(
        "Samsung",
        setOf("samsung"),
        listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        "samsung",
    ),
    Huawei(
        "Huawei or Honor",
        setOf("huawei", "honor"),
        listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        "huawei",
    ),
    OnePlus(
        "OnePlus",
        setOf("oneplus"),
        listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        "oneplus",
    ),
    Oppo(
        "Oppo or Realme",
        setOf("oppo", "realme"),
        listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "oppo",
    ),
    Vivo(
        "Vivo or iQOO",
        setOf("vivo", "iqoo"),
        listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        "vivo",
    ),
    ;

    val dontKillMyAppUrl: String get() = "https://dontkillmyapp.com/$dontKillMyAppSlug"

    companion object {
        /** The vendor for a device's `Build.MANUFACTURER` and `Build.BRAND`, or null for stock-like phones. */
        fun detect(manufacturer: String?, brand: String? = null): OemVendor? {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            return entries.firstOrNull { vendor -> names.any { it in vendor.manufacturers } }
        }
    }
}
