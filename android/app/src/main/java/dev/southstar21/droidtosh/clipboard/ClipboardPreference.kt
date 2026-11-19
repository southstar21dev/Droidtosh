package dev.southstar21.droidtosh.clipboard

import android.content.Context

/** The user's clipboard-sync switch, shared by the service and the share target. */
object ClipboardPreference {
    private const val FILE = "droidsync_clipboard"
    private const val KEY = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
}
