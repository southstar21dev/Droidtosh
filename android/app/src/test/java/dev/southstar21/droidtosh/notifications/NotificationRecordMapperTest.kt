package dev.southstar21.droidtosh.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
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
class NotificationRecordMapperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun replyNotification(): Notification {
        val intent = Intent().setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val reply = Notification.Action.Builder(
            0,
            "Reply",
            pendingIntent,
        ).addRemoteInput(
            RemoteInput.Builder("reply").setLabel("Message").build()
        ).build()
        val like = Notification.Action.Builder(
            0,
            "Like",
            PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(context, "messages")
            .setContentTitle("Alice")
            .setContentText("See you at 7")
            .setStyle(Notification.BigTextStyle().bigText("See you at 7, bring snacks"))
            .setSubText("Chat")
            .setCategory("msg")
            .addAction(reply)
            .addAction(like)
            .build()
    }

    @Test
    fun mapsFieldsAndActions() {
        val record = NotificationRecordMapper.toRecord(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            key = "0|com.whatsapp|1|null|10123",
            notification = replyNotification(),
            postTimeMs = 1_730_000_000_001,
        )
        assertEquals("0|com.whatsapp|1|null|10123", record.key)
        assertEquals("WhatsApp", record.appLabel)
        assertEquals("Alice", record.title)
        assertEquals("See you at 7", record.text)
        assertEquals("See you at 7, bring snacks", record.bigText)
        assertEquals("Chat", record.subText)
        assertEquals("msg", record.category)
        assertEquals(1_730_000_000_001, record.postedAtMs)
        assertEquals(2, record.actionsCount)
        assertTrue(record.actionsList[0].hasRemoteInput)
        assertEquals("Reply", record.actionsList[0].title)
        assertFalse(record.actionsList[1].hasRemoteInput)
        assertEquals("Message", record.remoteInputHint)
    }

    @Test
    fun marksOngoingNotifications() {
        val ongoing = Notification.Builder(context, "service")
            .setContentTitle("Syncing")
            .setOngoing(true)
            .build()
        val record = NotificationRecordMapper.toRecord("com.example", "Example", "k", ongoing, 1L)
        assertTrue(record.isOngoing)
    }

    @Test
    fun clipsOversizedText() {
        val huge = "x".repeat(NotificationRecordMapper.MAX_TEXT_CHARS * 10)
        // Builder setters already cap length; apps can still write extras directly.
        val notification = Notification.Builder(context, "mail")
            .setContentTitle("Digest")
            .build()
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, huge)
        val record = NotificationRecordMapper.toRecord("com.example", "Example", "k", notification, 1L)
        assertEquals(NotificationRecordMapper.MAX_TEXT_CHARS + 1, record.bigText.length)
        assertTrue(record.bigText.endsWith("…"))
    }

    @Test
    fun clipDoesNotSplitSurrogatePairs() {
        val clipped = NotificationRecordMapper.clip("ab\uD83D\uDE00cd", 3)
        assertEquals("ab…", clipped)
    }

    @Test
    fun reportsATruncatedRecordOnceEvenWhenSeveralFieldsAreClipped() {
        val long = "x".repeat(NotificationRecordMapper.MAX_TEXT_CHARS + 10)
        // As BigTextStyle does: the same long text in EXTRA_TEXT and EXTRA_BIG_TEXT (and a long title).
        val notification = Notification.Builder(context, "messages").build()
        for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)) {
            notification.extras.putCharSequence(key, long)
        }
        var reports = 0
        NotificationRecordMapper.toRecord("pkg", "App", "k", notification, 0L, onTruncated = { reports += 1 })
        assertEquals(1, reports)
        NotificationRecordMapper.toRecord("pkg", "App", "k", replyNotification(), 0L, onTruncated = { reports += 1 })
        assertEquals(1, reports)
    }

    @Test
    fun truncationCounterCountsEachNotificationOnce() {
        var count = 0
        val counter = TruncationCounter { count += 1 }
        counter.onTruncated("a")
        // The same notification updated, or mapped again for a snapshot.
        counter.onTruncated("a")
        counter.onTruncated("a")
        counter.onTruncated("b")
        assertEquals(2, count)
        counter.onRemoved("a")
        counter.onTruncated("a")
        assertEquals(3, count)
    }
}
