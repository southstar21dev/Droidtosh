package dev.southstar21.droidtosh.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.MainActivity

/** What the share sheet's Direct Share row should hold for the current pairing. */
sealed interface ShareTargetPlan {
    data object Remove : ShareTargetPlan
    data class Publish(val id: String, val label: String) : ShareTargetPlan
}

/**
 * The paired Mac as a Direct Share target (a long-lived sharing shortcut). Android decides whether and
 * where it shows in the share sheet; this only keeps one shortcut published while a Mac is paired.
 * Shares through it arrive at the same two activities as the app-list targets.
 */
object ShareTarget {
    /** Matches the category in res/xml/shortcuts.xml. */
    const val CATEGORY = "dev.southstar21.droidtosh.category.SEND_TO_MAC"
    const val FALLBACK_LABEL = "Mac"
    private const val MAX_LABEL = 40
    private const val PREFS = "droidtosh_share_target"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_LABEL = "label"

    /** Stable for one pairing, without putting the whole fingerprint in the system's shortcut list. */
    fun shortcutId(fingerprint: String): String = "mac-" + fingerprint.take(16)

    fun label(macName: String?): String =
        macName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL) ?: FALLBACK_LABEL

    fun plan(fingerprint: String?, macName: String?): ShareTargetPlan =
        if (fingerprint.isNullOrEmpty()) ShareTargetPlan.Remove
        else ShareTargetPlan.Publish(shortcutId(fingerprint), label(macName))

    /** Remembers the Mac's name (known after the first connected session) and refreshes the shortcut. */
    fun onMacName(context: Context, fingerprint: String?, macName: String) {
        if (macName.isNotBlank()) prefs(context).edit().putString(KEY_NAME, macName).apply()
        sync(context, fingerprint)
    }

    /**
     * Publishes or removes the shortcut to match the pairing. Safe to call as often as needed: an
     * unchanged shortcut (same pairing and label) is not pushed again, so every reconnect stays cheap.
     */
    fun sync(context: Context, fingerprint: String?) {
        val app = context.applicationContext
        runCatching {
            when (val plan = plan(fingerprint, prefs(app).getString(KEY_NAME, null))) {
                is ShareTargetPlan.Remove -> remove(app)
                is ShareTargetPlan.Publish -> {
                    val previous = prefs(app).getString(KEY_ID, null)
                    if (previous == plan.id && prefs(app).getString(KEY_LABEL, null) == plan.label) return@runCatching
                    if (previous != null && previous != plan.id) {
                        ShortcutManagerCompat.removeLongLivedShortcuts(app, listOf(previous))
                    }
                    ShortcutManagerCompat.pushDynamicShortcut(app, build(app, plan))
                    prefs(app).edit().putString(KEY_ID, plan.id).putString(KEY_LABEL, plan.label).apply()
                }
            }
        }
    }

    /** Called when the pairing is gone. */
    fun remove(context: Context) {
        val app = context.applicationContext
        runCatching {
            val id = prefs(app).getString(KEY_ID, null)
            if (id != null) ShortcutManagerCompat.removeLongLivedShortcuts(app, listOf(id))
            prefs(app).edit().clear().apply()
        }
    }

    /** Tells Android a share went through the shortcut, so it ranks higher. */
    fun reportUsed(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context.applicationContext, id) }
    }

    private fun build(context: Context, plan: ShareTargetPlan.Publish): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, plan.id)
            .setShortLabel(plan.label)
            .setLongLabel(plan.label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setCategories(setOf(CATEGORY))
            .setLongLived(true)
            .build()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
