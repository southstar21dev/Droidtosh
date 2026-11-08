package dev.southstar21.droidtosh.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

data class ActiveNotification(val key: String, val notification: Notification)

data class ActionOutcome(
    val ok: Boolean,
    val errorCode: String = "",
    val message: String = "",
)

data class SendResult(val ok: Boolean, val error: String = "")

fun interface PendingIntentSender {
    fun send(action: Notification.Action, replyText: String?): SendResult
}

class NotificationActionExecutor(
    private val activeNotifications: () -> List<ActiveNotification>,
    private val sender: PendingIntentSender,
) {
    fun execute(key: String, actionIndex: Int, replyText: String?): ActionOutcome {
        val active = activeNotifications().firstOrNull { it.key == key }
            ?: return ActionOutcome(false, "notification_not_found", "notification is no longer active")
        val actions = active.notification.actions
            ?: return ActionOutcome(false, "no_actions", "notification has no actions")
        if (actionIndex !in actions.indices) {
            return ActionOutcome(false, "bad_action_index", "action index out of range")
        }
        val result = sender.send(actions[actionIndex], replyText)
        return if (result.ok) {
            ActionOutcome(true)
        } else {
            ActionOutcome(false, "send_failed", result.error.ifEmpty { "pending intent failed" })
        }
    }
}

class AndroidPendingIntentSender(private val context: Context) : PendingIntentSender {
    override fun send(action: Notification.Action, replyText: String?): SendResult {
        return try {
            val intent = Intent()
            val remoteInputs = action.remoteInputs
            if (replyText != null && !remoteInputs.isNullOrEmpty()) {
                val results = Bundle().apply {
                    putCharSequence(remoteInputs[0].resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                action.actionIntent.send(context, 0, intent, null, null, null, options.toBundle())
            } else {
                action.actionIntent.send(context, 0, intent)
            }
            android.util.Log.d("DroidsyncCalls", "send action title=${action.title} ok=true")
            SendResult(true)
        } catch (error: Exception) {
            android.util.Log.d("DroidsyncCalls", "send action title=${action.title} failed: $error")
            SendResult(false, "${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
