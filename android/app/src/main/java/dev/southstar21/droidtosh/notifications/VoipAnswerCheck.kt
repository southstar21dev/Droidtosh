package dev.southstar21.droidtosh.notifications

/**
 * What the Mac hears about Answer on a VoIP call, checked a moment after the app's Answer action
 * was sent. That action usually opens the app's call screen, and Android 14+ silently drops an
 * activity start from a background app (PendingIntent.send still succeeds) unless the app is
 * visible or allowed to "Display over other apps". On a locked phone the VoIP app's own
 * full-screen call screen is showing, which lets the start through.
 */
object VoipAnswerCheck {
    const val BLOCKED = "answer_blocked"
    const val NO_EFFECT = "answer_no_effect"

    /**
     * @param answered the call shows as in progress.
     * @param mayStartActivities Droidtosh is visible or may draw over other apps.
     * @param keyguardLocked the phone is locked.
     */
    fun outcome(answered: Boolean, mayStartActivities: Boolean, keyguardLocked: Boolean, appLabel: String): ActionOutcome =
        when {
            answered -> ActionOutcome(true)
            !mayStartActivities && !keyguardLocked -> ActionOutcome(
                false,
                BLOCKED,
                "Android blocked answering $appLabel from the background while the phone is unlocked. " +
                    "Allow Droidtosh to display over other apps (Droidtosh → Settings → Calls), " +
                    "or answer on the phone.",
            )
            else -> ActionOutcome(false, NO_EFFECT, "The call didn't connect; $appLabel was opened on the phone to answer there.")
        }

    /** Whether to warn up front that the Answer is likely to be dropped. */
    fun likelyBlocked(mayStartActivities: Boolean, keyguardLocked: Boolean): Boolean =
        !mayStartActivities && !keyguardLocked
}
