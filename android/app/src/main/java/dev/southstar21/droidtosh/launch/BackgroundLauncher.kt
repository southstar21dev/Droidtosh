package dev.southstar21.droidtosh.launch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.diagnostics.Diagnostics

/** Whether one of our activities is started; a visible window may launch activities freely. */
object AppVisibility {
    @Volatile
    private var started = 0

    val visible: Boolean get() = started > 0

    @Synchronized
    fun onActivityStarted() {
        started += 1
    }

    @Synchronized
    fun onActivityStopped() {
        started = (started - 1).coerceAtLeast(0)
    }
}

/**
 * "Display over other apps" (SYSTEM_ALERT_WINDOW). Android exempts apps holding it from the
 * background activity launch restrictions, so with it Droidtosh may open apps, and send other
 * apps' notification actions that open an activity (a VoIP app's Answer), while in the background.
 */
object OverlayPermission {
    fun granted(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** The system page where the user allows it for Droidtosh. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))
}

enum class LaunchMode {
    /** startActivity is allowed: one of our windows is visible, or we may draw over other apps. */
    DIRECT,

    /** startActivity is likely blocked; try it, and post a tap-to-open notification as well. */
    DIRECT_WITH_NOTIFICATION,
}

object LaunchPolicy {
    fun decide(appVisible: Boolean, canDrawOverlays: Boolean = false): LaunchMode =
        if (appVisible || canDrawOverlays) LaunchMode.DIRECT else LaunchMode.DIRECT_WITH_NOTIFICATION
}

/**
 * Opens another app at the Mac's request. A background start is silently dropped by Android unless
 * one of our windows is visible or the user allowed "Display over other apps", and there's no way
 * to detect the drop, so otherwise a heads-up notification lets the user get there with one tap.
 */
object BackgroundLauncher {
    private const val TAG = "DroidsyncLaunch"
    private const val CHANNEL_ID = "open_on_phone"
    private const val NOTIFICATION_TIMEOUT_MS = 60_000L

    fun openApp(context: Context, packageName: String, label: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mode = LaunchPolicy.decide(AppVisibility.visible, OverlayPermission.granted(context))
        Diagnostics.recorder.onInfo("open app ($packageName): ${mode.name.lowercase()}")
        val started = runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "startActivity failed for $packageName", it) }
            .isSuccess
        if (mode == LaunchMode.DIRECT_WITH_NOTIFICATION || !started) postTapToOpen(context, packageName, label, intent)
        return true
    }

    private fun postTapToOpen(context: Context, packageName: String, label: String, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Open on phone", NotificationManager.IMPORTANCE_HIGH)
        )
        // Sent by the system on tap, which is always allowed to start an activity.
        val contentIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_glyph)
            .setContentTitle("Open $label")
            .setContentText("Requested from your Mac — tap to open")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        runCatching { manager.notify(packageName, NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "couldn't post tap-to-open for $packageName", it) }
    }

    private const val NOTIFICATION_ID = 7
}
