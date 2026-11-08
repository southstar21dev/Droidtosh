package dev.southstar21.droidtosh.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.transport.FakeDuplexStream
import dev.southstar21.droidtosh.transport.ProtocolClient
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendClipboardActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val psk = ByteArray(32) { 0x42 }
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun readsClipboardOnWindowFocusAndFinishes() = runBlocking<Unit> {
        val stream = attachHub()
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("t", "focused send"))
            val activity = Robolectric.buildActivity(SendClipboardActivity::class.java).create().get()

            activity.onWindowFocusChanged(true)

            assertTrue("activity should finish after dispatching", activity.isFinishing)
            val envelope = awaitEnvelope(stream) { it.hasClipboardText() }
            assertEquals("focused send", envelope.clipboardText.text)
        } finally {
            TransportHub.detach()
        }
    }

    @Test
    fun doesNotSendTwiceWhenFocusRepeats() = runBlocking<Unit> {
        val stream = attachHub()
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("t", "once"))
            val activity = Robolectric.buildActivity(SendClipboardActivity::class.java).create().get()

            activity.onWindowFocusChanged(true)
            activity.onWindowFocusChanged(true)

            delay(300)
            val sends = synchronized(stream.written) { stream.written.toList() }
                .map { Envelope.parseFrom(it) }
                .count { it.hasClipboardText() }
            assertEquals(1, sends)
        } finally {
            TransportHub.detach()
        }
    }

    @Test
    fun lostFocusDoesNotDispatchOrFinish() {
        clipboard.clearPrimaryClip()
        val activity = Robolectric.buildActivity(SendClipboardActivity::class.java).create().get()

        activity.onWindowFocusChanged(false)

        assertFalse(activity.isFinishing)
        activity.finish()
    }

    private fun attachHub(): FakeDuplexStream {
        TransportHub.detach()
        val stream = FakeDuplexStream()
        TransportHub.attach(ProtocolClient(stream, psk) { ByteArray(32) { 0x01 } })
        return stream
    }

    private suspend fun awaitEnvelope(
        stream: FakeDuplexStream,
        predicate: (Envelope) -> Boolean,
    ): Envelope {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val envelopes = synchronized(stream.written) { stream.written.toList() }
                .map { Envelope.parseFrom(it) }
            envelopes.firstOrNull(predicate)?.let { return it }
            delay(25)
        }
        throw AssertionError("expected envelope was not written")
    }
}
