package dev.southstar21.droidtosh.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationActionExecutorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingSender : PendingIntentSender {
        var lastTitle: String? = null
        var lastReply: String? = null
        var result = SendResult(true)
        override fun send(action: Notification.Action, replyText: String?): SendResult {
            lastTitle = action.title?.toString()
            lastReply = replyText
            return result
        }
    }

    private fun notification(): Notification =
        Notification.Builder(context, "chat")
            .setContentTitle("Alice")
            .addAction(
                Notification.Action.Builder(
                    0, "Reply",
                    PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE),
                ).build()
            )
            .build()

    @Test
    fun sendsSelectedActionWithReply() {
        val sender = RecordingSender()
        val executor = NotificationActionExecutor(
            activeNotifications = { listOf(ActiveNotification("k1", notification())) },
            sender = sender,
        )
        val outcome = executor.execute("k1", 0, "hello")
        assertTrue(outcome.ok)
        assertEquals("Reply", sender.lastTitle)
        assertEquals("hello", sender.lastReply)
    }

    @Test
    fun failsForMissingNotification() {
        val executor = NotificationActionExecutor(activeNotifications = { emptyList() }, sender = RecordingSender())
        val outcome = executor.execute("missing", 0, null)
        assertFalse(outcome.ok)
        assertEquals("notification_not_found", outcome.errorCode)
    }

    @Test
    fun failsForBadActionIndex() {
        val executor = NotificationActionExecutor(
            activeNotifications = { listOf(ActiveNotification("k1", notification())) },
            sender = RecordingSender(),
        )
        val outcome = executor.execute("k1", 5, null)
        assertFalse(outcome.ok)
        assertEquals("bad_action_index", outcome.errorCode)
    }

    @Test
    fun propagatesSenderFailure() {
        val sender = RecordingSender().apply { result = SendResult(false, "boom") }
        val executor = NotificationActionExecutor(
            activeNotifications = { listOf(ActiveNotification("k1", notification())) },
            sender = sender,
        )
        val outcome = executor.execute("k1", 0, null)
        assertFalse(outcome.ok)
        assertEquals("send_failed", outcome.errorCode)
        assertEquals("boom", outcome.message)
    }
}
