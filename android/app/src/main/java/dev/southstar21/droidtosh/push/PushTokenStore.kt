package dev.southstar21.droidtosh.push

import android.content.Context

class PushTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val token: String?
        get() = preferences.getString(KEY_TOKEN, null)

    fun set(token: String) {
        preferences.edit().putString(KEY_TOKEN, token).apply()
    }

    companion object {
        const val PREFS_NAME = "droidsync_push"
        const val KEY_TOKEN = "token"
    }
}
