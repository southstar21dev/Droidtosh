package dev.southstar21.droidtosh.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.transport.FakeDuplexStream
import dev.southstar21.droidtosh.transport.ProtocolClient
import dev.southstar21.droidtosh.transport.TransportHub
import java.io.File
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardSyncManagerSendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val psk = ByteArray(32) { 0x42 }
    private val png = Base64.getDecoder().decode(PNG_BASE64)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun sendCurrentClipboardSendsText() = runBlocking<Unit> {
        val stream = attachHub()
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("t", "hello from phone"))
            val manager = ClipboardSyncManager(context, isEnabled = { false })
            try {
                assertTrue(manager.sendCurrentClipboard())
                val envelope = awaitEnvelope(stream) { it.hasClipboardText() }
                assertEquals("hello from phone", envelope.clipboardText.text)
            } finally {
                manager.stop()
            }
        } finally {
            TransportHub.detach()
        }
    }

    @Test
    fun sendCurrentClipboardSendsImageUri() = runBlocking<Unit> {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        File(dir, "send.png").writeBytes(png)
        val authority = "${context.packageName}.clipboard"
        val provider = ClipboardImageProvider()
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath("send.png").build()
        val stream = attachHub()
        try {
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "img", uri))
            val manager = ClipboardSyncManager(context, isEnabled = { false })
            try {
                assertTrue(manager.sendCurrentClipboard())
                val start = awaitEnvelope(stream) { it.hasClipboardBlobStart() }
                assertEquals("image/png", start.clipboardBlobStart.mimeType)
                awaitEnvelope(stream) { it.hasClipboardBlobEnd() }
            } finally {
                manager.stop()
            }
        } finally {
            TransportHub.detach()
        }
    }

    @Test
    fun sendCurrentClipboardReturnsFalseForEmptyClipboard() {
        clipboard.clearPrimaryClip()
        val manager = ClipboardSyncManager(context, isEnabled = { false })
        try {
            assertFalse(manager.sendCurrentClipboard())
        } finally {
            manager.stop()
        }
    }

    @Test
    fun sendCurrentClipboardReturnsFalseForEmptyText() {
        clipboard.setPrimaryClip(ClipData.newPlainText("t", ""))
        val manager = ClipboardSyncManager(context, isEnabled = { false })
        try {
            assertFalse(manager.sendCurrentClipboard())
        } finally {
            manager.stop()
        }
    }

    @Test
    fun sendCurrentClipboardReturnsFalseForOversizedText() {
        val text = "x".repeat(ClipboardTransfer.MAX_TEXT_SIZE + 1)
        clipboard.setPrimaryClip(ClipData.newPlainText("t", text))
        val manager = ClipboardSyncManager(context, isEnabled = { false })
        try {
            assertFalse(manager.sendCurrentClipboard())
        } finally {
            manager.stop()
        }
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

    private companion object {
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="
    }
}
