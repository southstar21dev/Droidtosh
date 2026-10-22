package dev.southstar21.droidtosh.calls

import android.app.Notification
import dev.southstar21.droidtosh.protocol.v1.CallPhase

data class VoipCallSignal(
    val packageName: String,
    val title: String,
    val text: String,
    val actions: List<Notification.Action>,
    val actionIndices: Map<VoipAction, Int>,
    val isOngoing: Boolean,
)

class CallNotificationClassifier(
    private val matcher: VoipActionMatcher,
    private val defaultDialerPackage: String? = null,
) {
    fun classify(packageName: String, notification: Notification): VoipCallSignal? {
        if (!defaultDialerPackage.isNullOrEmpty() && packageName == defaultDialerPackage) return null
        if (packageName == "android" ||
            packageName.startsWith("com.android.server") ||
            packageName.startsWith("com.android.internal")
        ) {
            return null
        }
        val actions = notification.actions.orEmpty().toList()
        android.util.Log.d("DroidsyncCalls", "classify pkg=$packageName category=${notification.category} actions=${actions.map { it.title }}")
        val isCategoryCall = notification.category == Notification.CATEGORY_CALL
        val matches = matcher.match(
            packageName,
            actions,
            allowGeneric = isCategoryCall || matcher.hasProfile(packageName),
        )
        val hasAnswerAndDecline =
            matches.containsKey(VoipAction.ACCEPT) &&
                (matches.containsKey(VoipAction.DECLINE) || matches.containsKey(VoipAction.END))
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        // An answered call swaps the incoming notification for an ongoing one with only "Hang up";
        // for a known calling app that is the call in progress even without CATEGORY_CALL.
        val isOngoingCall =
            isOngoing && matcher.hasProfile(packageName) && matches.containsKey(VoipAction.END)
        if (!isCategoryCall && !hasAnswerAndDecline && !isOngoingCall) return null
        val extras = notification.extras
        return VoipCallSignal(
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            actions = actions,
            actionIndices = matches,
            isOngoing = isOngoing,
        )
    }

    companion object {
        fun phaseFor(signal: VoipCallSignal, wasActive: Boolean): CallPhase =
            when {
                signal.actionIndices.containsKey(VoipAction.ACCEPT) -> CallPhase.CALL_PHASE_RINGING
                signal.isOngoing || wasActive -> CallPhase.CALL_PHASE_ACTIVE
                else -> CallPhase.CALL_PHASE_RINGING
            }
    }
}
