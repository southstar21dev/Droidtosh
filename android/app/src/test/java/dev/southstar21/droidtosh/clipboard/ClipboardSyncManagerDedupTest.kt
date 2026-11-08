package dev.southstar21.droidtosh.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.ClipboardText
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.transport.FakeDuplexStream
import dev.southstar21.droidtosh.transport.ProtocolClient
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardSyncManagerDedupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val psk = ByteArray(32) { 0x42 }
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun recopyAfterRemoteChangeIsSentAgain() = runBlocking<Unit> {
        val stream = attachHub()
        val manager = ClipboardSyncManager(context, isEnabled = { true })
        try {
            manager.start()
            delay(200)
            clipboard.setPrimaryClip(ClipData.newPlainText("t", "phone text"))
            awaitSentTexts(stream, 1)

            TransportHub.dispatch(macText("mac text"))
            awaitClipboardText("mac text")
            delay(200)

            clipboard.setPrimaryClip(ClipData.newPlainText("t", "phone text"))
            assertEquals(listOf("phone text", "phone text"), awaitSentTexts(stream, 2))
        } finally {
            manager.stop()
            TransportHub.detach()
        }
    }

    @Test
    fun incomingTextIsIgnoredWhileDisabled() = runBlocking<Unit> {
        attachHub()
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "original"))
        val manager = ClipboardSyncManager(context, isEnabled = { false })
        try {
            manager.start()
            delay(200)
            TransportHub.dispatch(macText("from mac"))
            delay(300)
            assertEquals("original", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            manager.stop()
            TransportHub.detach()
        }
    }

    private fun macText(text: String) = newEnvelope {
        setClipboardText(ClipboardText.newBuilder().setText(text).setCopiedAtMs(1L))
    }

    private fun attachHub(): FakeDuplexStream {
        TransportHub.detach()
        val stream = FakeDuplexStream()
        TransportHub.attach(ProtocolClient(stream, psk) { ByteArray(32) { 0x01 } })
        return stream
    }

    private suspend fun awaitClipboardText(expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == expected) return
            delay(25)
        }
        throw AssertionError("clipboard never became \"$expected\"")
    }

    private suspend fun awaitSentTexts(stream: FakeDuplexStream, count: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val texts = synchronized(stream.written) { stream.written.toList() }
                .map { Envelope.parseFrom(it) }
                .filter { it.hasClipboardText() }
                .map { it.clipboardText.text }
            if (texts.size >= count) return texts
            delay(25)
        }
        throw AssertionError("expected $count clipboard texts to be sent")
    }
}
