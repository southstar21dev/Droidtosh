package dev.southstar21.droidtosh.notifications

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 15 hides the content of sensitive notifications (one-time codes) from notification
 * listeners that lack RECEIVE_SENSITIVE_NOTIFICATIONS, replacing the text with a placeholder.
 * No public API reports this, so the placeholder is what gives it away. The permission can
 * only be granted over adb, so the Notifications tab shows the command once.
 */
object SensitiveNotifications {
    const val OP = "android:receive_sensitive_notifications"
    const val ENGLISH_PLACEHOLDER = "Sensitive notification content hidden"

    /** The adb command that lets Droidtosh read sensitive notifications. */
    fun adbCommand(packageName: String): String =
        "adb shell appops set --user 0 $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"

    /** True when [text] is the system's placeholder, in its own language or in English. */
    fun isRedactionPlaceholder(text: CharSequence?, systemPlaceholder: String?): Boolean {
        val value = text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value.equals(ENGLISH_PLACEHOLDER, ignoreCase = true) ||
            (!systemPlaceholder.isNullOrBlank() && value.equals(systemPlaceholder.trim(), ignoreCase = true))
    }

    @SuppressLint("DiscouragedApi")
    private fun systemPlaceholder(): String? = runCatching {
        val id = Resources.getSystem().getIdentifier("redacted_notification_message", "string", "android")
        if (id == 0) null else Resources.getSystem().getString(id)
    }.getOrNull()

    /** Whether a notification's text is the placeholder (Android 15 and later only). */
    fun isRedacted(text: CharSequence?): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            isRedactionPlaceholder(text, systemPlaceholder())

    fun isAllowed(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        appOps.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private const val PREFS = "droidtosh_sensitive_notifications"
    private const val SEEN = "redaction_seen"
    private const val DISMISSED = "hint_dismissed"

    private val seen = MutableStateFlow(false)
    private val dismissed = MutableStateFlow(false)
    private val hint = MutableStateFlow(false)
    @Volatile private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            seen.value = prefs.getBoolean(SEEN, false)
            dismissed.value = prefs.getBoolean(DISMISSED, false)
            loaded = true
        }
    }

    /** Whether the hint should show: a redacted notification was seen, access is still missing, not dismissed. */
    fun hintVisible(context: Context): StateFlow<Boolean> {
        load(context)
        refresh(context)
        return hint.asStateFlow()
    }

    private fun refresh(context: Context) {
        hint.value = seen.value && !dismissed.value && !isAllowed(context)
    }

    /** Records that Android hid a notification's content from us. */
    fun onRedactedSeen(context: Context) {
        load(context)
        if (!seen.value) {
            seen.value = true
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(SEEN, true).apply()
        }
        refresh(context)
    }

    fun dismissHint(context: Context) {
        load(context)
        dismissed.value = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(DISMISSED, true).apply()
        refresh(context)
    }
}
