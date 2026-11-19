package dev.southstar21.droidtosh.push

import android.content.Context
import dev.southstar21.droidtosh.BuildConfig

class PushTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The FCM registration token. */
    val token: String?
        get() = preferences.getString(KEY_TOKEN, null)

    fun set(token: String) {
        preferences.edit().putString(KEY_TOKEN, token).apply()
    }

    /** The UnifiedPush endpoint URL the distributor gave us; a capability URL, never log it. */
    var unifiedPushEndpoint: String?
        get() = preferences.getString(KEY_UP_ENDPOINT, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_UP_ENDPOINT) else putString(KEY_UP_ENDPOINT, value)
            }.apply()
        }

    /** The user's explicit choice; null until they pick one (then it is automatic). */
    var chosenProvider: WakeProvider?
        get() = preferences.getString(KEY_PROVIDER, null)
            ?.let { name -> WakeProvider.entries.firstOrNull { it.name == name } }
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_PROVIDER) else putString(KEY_PROVIDER, value.name)
            }.apply()
        }

    fun provider(): WakeProvider =
        WakeToken.effective(chosenProvider, BuildConfig.FCM_ENABLED, unifiedPushEndpoint != null)

    /** What the join line carries: the FCM token, "up:<endpoint>", or null (a push-less join). */
    fun joinToken(): String? = when (provider()) {
        WakeProvider.FCM -> token
        WakeProvider.UNIFIEDPUSH -> WakeToken.unifiedPush(unifiedPushEndpoint)
        WakeProvider.NONE -> null
    }

    companion object {
        const val PREFS_NAME = "droidsync_push"
        const val KEY_TOKEN = "token"
        const val KEY_UP_ENDPOINT = "up_endpoint"
        const val KEY_PROVIDER = "provider"
    }
}
