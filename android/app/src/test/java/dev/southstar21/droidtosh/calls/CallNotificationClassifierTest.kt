package dev.southstar21.droidtosh.calls

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotificationClassifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val classifier = CallNotificationClassifier(
        VoipActionMatcher(VoipProfiles.load(context), VoipProfiles.generic(context))
    )

    private fun action(title: String): Notification.Action =
        Notification.Action.Builder(
            0,
            title,
            PendingIntent.getBroadcast(context, title.hashCode(), Intent(), PendingIntent.FLAG_IMMUTABLE),
        ).build()

    @Test
    fun classifiesWhatsAppCallNotification() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setContentText("Incoming video call")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(action("Decline"))
            .addAction(action("Answer"))
            .build()
        val signal = classifier.classify("com.whatsapp", notification)
        assertNotNull(signal)
        assertEquals("Alice", signal!!.title)
        assertEquals(1, signal.actionIndices[VoipAction.ACCEPT])
        assertEquals(0, signal.actionIndices[VoipAction.DECLINE])
    }

    @Test
    fun classifiesCategoryCallFromUnknownPackage() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("+15551234567")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(action("Answer"))
            .build()
        assertNotNull(classifier.classify("com.example.voip", notification))
    }

    @Test
    fun classifiesProfileAppWithMatchingActionsEvenWithoutCategory() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Bob")
            .addAction(action("Answer"))
            .addAction(action("Decline"))
            .build()
        assertNotNull(classifier.classify("org.telegram.messenger", notification))
    }

    @Test
    fun classifiesAnswerAndEndPairWithoutCategory() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Bob")
            .addAction(action("Answer"))
            .addAction(action("Hang up"))
            .build()
        val signal = classifier.classify("org.telegram.messenger", notification)
        assertNotNull(signal)
        assertEquals(0, signal!!.actionIndices[VoipAction.ACCEPT])
        assertEquals(1, signal.actionIndices[VoipAction.END])
    }

    @Test
    fun ignoresFullScreenIntentWithoutCallActions() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setFullScreenIntent(
                PendingIntent.getBroadcast(context, 1, Intent(), PendingIntent.FLAG_IMMUTABLE),
                true,
            )
            .build()
        assertNull(classifier.classify("com.example.voip", notification))
    }

    @Test
    fun classifiesOngoingCategoryCall() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        val signal = classifier.classify("com.example.voip", notification)
        assertNotNull(signal)
        assertTrue(signal!!.isOngoing)
    }

    @Test
    fun ignoresDefaultDialerPackage() {
        val dialerClassifier = CallNotificationClassifier(
            VoipActionMatcher(VoipProfiles.load(context), VoipProfiles.generic(context)),
            defaultDialerPackage = "com.google.android.dialer",
        )
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(action("Answer"))
            .addAction(action("Decline"))
            .build()
        assertNull(dialerClassifier.classify("com.google.android.dialer", notification))
        assertNotNull(dialerClassifier.classify("com.example.voip", notification))
    }

    @Test
    fun ignoresInviteWithAcceptAndDismissFromUnknownPackage() {
        val notification = Notification.Builder(context, "invites")
            .setContentTitle("Team sync")
            .setContentText("Invitation from Bob")
            .addAction(action("Accept"))
            .addAction(action("Dismiss"))
            .build()
        assertNull(classifier.classify("com.example.calendar", notification))
    }

    @Test
    fun ignoresRegularMessageNotifications() {
        val notification = Notification.Builder(context, "messages")
            .setContentTitle("Alice")
            .setContentText("See you at 7")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(action("Reply"))
            .build()
        assertNull(classifier.classify("com.whatsapp", notification))
    }

    @Test
    fun phaseIsRingingWhenAcceptActionPresentEvenIfOngoing() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(action("Decline"))
            .addAction(action("Answer"))
            .build()
        val signal = classifier.classify("com.whatsapp", notification)
        assertNotNull(signal)
        assertTrue(signal!!.isOngoing)
        assertEquals(
            CallPhase.CALL_PHASE_RINGING,
            CallNotificationClassifier.phaseFor(signal, wasActive = false),
        )
    }

    @Test
    fun phaseIsActiveWithoutAcceptActionWhenOngoing() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        val signal = classifier.classify("com.example.voip", notification)
        assertNotNull(signal)
        assertEquals(
            CallPhase.CALL_PHASE_ACTIVE,
            CallNotificationClassifier.phaseFor(signal!!, wasActive = false),
        )
    }

    @Test
    fun phaseIsActiveWithoutAcceptActionWhenPreviouslyActive() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        val signal = classifier.classify("com.example.voip", notification)
        assertNotNull(signal)
        assertEquals(
            CallPhase.CALL_PHASE_ACTIVE,
            CallNotificationClassifier.phaseFor(signal!!, wasActive = true),
        )
    }

    @Test
    fun phaseIsRingingWithoutAcceptActionWhenNotOngoingOrActive() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("Alice")
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        val signal = classifier.classify("com.example.voip", notification)
        assertNotNull(signal)
        assertEquals(
            CallPhase.CALL_PHASE_RINGING,
            CallNotificationClassifier.phaseFor(signal!!, wasActive = false),
        )
    }
    @Test
    fun whatsAppBusinessHasItsOwnProfileAndLabel() {
        val profile = VoipProfiles.load(context).firstOrNull { it.packageName == "com.whatsapp.w4b" }
        assertNotNull(profile)
        assertEquals("WhatsApp Business", profile!!.label)
    }

    @Test
    fun classifiesWhatsAppBusinessIncomingVoiceCall() {
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("+36 70 563 9709")
            .setContentText("Incoming voice call")
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(action("Decline"))
            .addAction(action("Answer"))
            .build()
        val signal = classifier.classify("com.whatsapp.w4b", notification)
        assertNotNull(signal)
        assertEquals("+36 70 563 9709", signal!!.title)
        assertEquals(1, signal.actionIndices[VoipAction.ACCEPT])
        assertEquals(0, signal.actionIndices[VoipAction.DECLINE])
        assertEquals(
            CallPhase.CALL_PHASE_RINGING,
            CallNotificationClassifier.phaseFor(signal, wasActive = false),
        )
    }

    @Test
    fun answeredWhatsAppBusinessCallIsActiveEvenWithoutCallCategory() {
        // After answering, WhatsApp posts "Ongoing voice call" with only a Hang up action.
        val notification = Notification.Builder(context, "calls")
            .setContentTitle("+36 70 563 9709")
            .setContentText("Ongoing voice call")
            .setOngoing(true)
            .addAction(action("Hang up"))
            .build()
        val signal = classifier.classify("com.whatsapp.w4b", notification)
        assertNotNull(signal)
        assertEquals(0, signal!!.actionIndices[VoipAction.END])
        assertEquals(
            CallPhase.CALL_PHASE_ACTIVE,
            CallNotificationClassifier.phaseFor(signal, wasActive = false),
        )
    }

    @Test
    fun ongoingHangUpFromUnknownAppWithoutCallCategoryIsNotACall() {
        val notification = Notification.Builder(context, "media")
            .setContentTitle("Recording")
            .setOngoing(true)
            .addAction(action("Hang up"))
            .build()
        assertNull(classifier.classify("com.example.recorder", notification))
    }
}
