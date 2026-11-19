package dev.southstar21.droidtosh.push

import android.app.Activity
import android.content.Context
import dev.southstar21.droidtosh.BuildConfig
import org.unifiedpush.android.connector.UnifiedPush

/** Registration with the user's UnifiedPush distributor (ntfy and friends). */
object UnifiedPushController {
    private const val INSTANCE = "default"

    fun distributorAvailable(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Registers when UnifiedPush is chosen, or when nothing else can wake the phone (no FCM in the
     * build and no explicit choice) and a distributor can be used without asking.
     */
    fun ensureRegistered(context: Context) {
        val chosen = PushTokenStore(context).chosenProvider
        val wanted = chosen == WakeProvider.UNIFIEDPUSH || (chosen == null && !BuildConfig.FCM_ENABLED)
        if (!wanted) return
        UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok ->
            if (ok) UnifiedPush.register(context, INSTANCE)
        }
    }

    /** Shows the library's distributor picker, then registers with the one chosen. */
    fun pickDistributor(activity: Activity, onDone: () -> Unit = {}) {
        UnifiedPush.tryPickDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity, INSTANCE)
            onDone()
        }
    }

    fun unregister(context: Context) {
        UnifiedPush.unregister(context, INSTANCE)
        PushTokenStore(context).unifiedPushEndpoint = null
    }
}
