package dev.southstar21.droidtosh.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobChunk
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobEnd
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobStart
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.transport.FakeDuplexStream
import dev.southstar21.droidtosh.transport.ProtocolClient
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import com.google.protobuf.ByteString
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardSyncManagerEchoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val psk = ByteArray(32) { 0x42 }
    private val png = Base64.getDecoder().decode(PNG_BASE64)

    @Test
    fun incomingImageIsAppliedWithoutEchoingBlobsBack() = runBlocking {
        TransportHub.detach()
        val authority = "${context.packageName}.clipboard"
        val provider = ClipboardImageProvider()
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        Shadows.shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true,
            ),
        )
        val stream = FakeDuplexStream()
        TransportHub.attach(ProtocolClient(stream, psk) { ByteArray(32) { 0x01 } })
        val manager = ClipboardSyncManager(context, isEnabled = { true })
        try {
            manager.start()
            delay(200)
            val transferId = "echo-test"
            TransportHub.dispatch(newEnvelope {
                setClipboardBlobStart(
                    ClipboardBlobStart.newBuilder()
                        .setTransferId(transferId)
                        .setMimeType("image/png")
                        .setTotalSize(png.size.toLong())
                        .setCopiedAtMs(1L)
                )
            })
            TransportHub.dispatch(newEnvelope {
                setClipboardBlobChunk(
                    ClipboardBlobChunk.newBuilder()
                        .setTransferId(transferId)
                        .setOffset(0)
                        .setData(ByteString.copyFrom(png))
                )
            })
            TransportHub.dispatch(newEnvelope {
                setClipboardBlobEnd(ClipboardBlobEnd.newBuilder().setTransferId(transferId))
            })

            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && clipboard.primaryClip == null) {
                delay(25)
            }
            val clip = clipboard.primaryClip
            assertNotNull("inbound image should be applied to the clipboard", clip)
            assertEquals("image/png", clip!!.description.getMimeType(0))

            delay(500)

            val outbound = synchronized(stream.written) { stream.written.toList() }
                .map { Envelope.parseFrom(it) }
            assertTrue(
                "an applied image must not be echoed back as clipboard blobs",
                outbound.none {
                    it.hasClipboardBlobStart() || it.hasClipboardBlobChunk() || it.hasClipboardBlobEnd()
                },
            )
        } finally {
            manager.stop()
            TransportHub.detach()
        }
    }

    private companion object {
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="
    }
}
