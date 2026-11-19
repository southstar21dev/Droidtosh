package dev.southstar21.droidtosh.background

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Opens the manufacturer's battery screens, and remembers that the user went through them. */
object BackgroundSettings {
    private const val PREFS = "droidtosh_background"
    private const val VENDOR_HANDLED = "vendor_handled"

    fun vendor(): OemVendor? = OemVendor.detect(Build.MANUFACTURER, Build.BRAND)

    fun vendorHandled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(VENDOR_HANDLED, false)

    private fun setVendorHandled(context: Context, handled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(VENDOR_HANDLED, handled).apply()
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /**
     * Opens the first known manufacturer screen that starts on this phone (package visibility hides
     * other apps' activities from a lookup, so each is simply tried), else the app's own settings
     * page, else the dontkillmyapp.com guide. Marks the step handled: Android can't tell whether the
     * user changed anything there.
     */
    fun openVendorSettings(context: Context) {
        val vendor = vendor() ?: return
        val opened = vendor.components.any { (pkg, cls) -> start(context, Intent().setComponent(ComponentName(pkg, cls))) } ||
            start(
                context,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            ) ||
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse(vendor.dontKillMyAppUrl)))
        if (opened) setVendorHandled(context, true)
    }
}
