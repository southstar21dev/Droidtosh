package dev.southstar21.droidtosh.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobChunk
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobEnd
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobStart
import dev.southstar21.droidtosh.protocol.v1.ClipboardText
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.PhoneStatus
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ClipboardSyncManager(
    private val context: Context,
    private val isEnabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private var assembler = ClipboardAssembler()
    private var lastAppliedHash: String? = null
    private var lastSentHash: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        clipboard.removePrimaryClipChangedListener(listener)
        clipboard.addPrimaryClipChangedListener(listener)
        scope.launch {
            TransportHub.incoming(InboundFeature.CLIPBOARD).collect(::handleIncoming)
        }
        scope.launch {
            TransportHub.isConnected.collect { connected ->
                if (connected) sendPhoneStatus()
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sendPhoneStatus()
            override fun onLost(network: Network) = sendPhoneStatus()
        }
        networkCallback = callback
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(callback)
        }.onFailure {
            networkCallback = null
            Log.w(TAG, "failed to register network callback", it)
        }
        sendPhoneStatus()
    }

    fun stop() {
        clipboard.removePrimaryClipChangedListener(listener)
        networkCallback?.let { callback ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        scope.cancel()
    }

    fun sendCurrentClipboard(): Boolean {
        val clip = clipboard.primaryClip ?: return false
        val item = clip.getItemAt(0)
        val description = clip.description
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val text = item.coerceToText(context).toString()
            if (text.isEmpty()) return false
            val size = text.toByteArray().size
            if (size > ClipboardTransfer.MAX_TEXT_SIZE) {
                Log.w(TAG, "clipboard text too large to send ($size bytes)")
                return false
            }
            sendSharedText(text)
            return true
        }
        val uri = item.uri ?: return false
        if (!description.hasMimeType("image/*")) return false
        sendSharedImage(uri)
        return true
    }

    fun sendSharedText(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray()
        if (bytes.size > ClipboardTransfer.MAX_TEXT_SIZE) {
            Log.w(TAG, "shared text too large to sync (${bytes.size} bytes)")
            return
        }
        noteSent(sha256(bytes))
        sendText(text)
    }

    private fun sendText(text: String) {
        val sent = TransportHub.send(newEnvelope {
            setClipboardText(
                ClipboardText.newBuilder()
                    .setText(text)
                    .setCopiedAtMs(System.currentTimeMillis())
            )
        })
        if (sent) Diagnostics.recorder.onClipboardSent()
    }

    fun sendSharedImage(uri: Uri) {
        scope.launch {
            val bytes = readImageBytes(uri) ?: return@launch
            sendImage(bytes)
        }
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        scope.launch { handleLocalClipboardChanged() }
    }

    private suspend fun handleLocalClipboardChanged() {
        if (!isEnabled()) return
        val clip = clipboard.primaryClip ?: return
        val description = clip.description
        if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            val text = clip.getItemAt(0).coerceToText(context).toString()
            if (text.isEmpty()) return
            val textBytes = text.toByteArray()
            if (textBytes.size > ClipboardTransfer.MAX_TEXT_SIZE) {
                Log.w(TAG, "clipboard text too large to sync (${textBytes.size} bytes)")
                return
            }
            val hash = sha256(textBytes)
            if (hash == lastAppliedHash || hash == lastSentHash) return
            noteSent(hash)
            sendText(text)
            return
        }
        if (!ClipboardPolicy.shouldAutoSend(isImage = true, metered = isMetered(), enabled = true)) {
            return
        }
        val imageBytes = readImageBytes(clip) ?: return
        val hash = sha256(imageBytes)
        if (hash == lastAppliedHash || hash == lastSentHash) return
        sendImage(imageBytes)
    }

    private fun readImageBytes(clip: ClipData): ByteArray? {
        val uri = clip.getItemAt(0).uri ?: return null
        return readImageBytes(uri, clip.description.filterMimeTypes("image/*")?.firstOrNull())
    }

    private fun readImageBytes(uri: Uri, fallbackMime: String? = null): ByteArray? {
        val declaredMime = context.contentResolver.getType(uri) ?: fallbackMime ?: return null
        val mime = declaredMime.lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
        if (mime !in ClipboardTransfer.ALLOWED_MIME) return null
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return normalizeToPng(raw, mime)
    }

    private fun normalizeToPng(bytes: ByteArray, mime: String): ByteArray? {
        val png = if (mime == "image/png" && isPng(bytes)) {
            bytes
        } else {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                .getOrNull()
            if (bitmap == null) {
                Log.w(TAG, "could not decode clipboard image (mime=$mime, ${bytes.size} bytes)")
                return null
            }
            val out = ByteArrayOutputStream()
            val encoded = runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                .getOrDefault(false)
            bitmap.recycle()
            if (!encoded) {
                Log.w(TAG, "could not re-encode clipboard image as PNG")
                return null
            }
            out.toByteArray()
        }
        if (png.size > ClipboardTransfer.MAX_BLOB_SIZE) {
            Log.w(TAG, "clipboard image too large to sync (${png.size} bytes)")
            return null
        }
        return png
    }

    // Awaits queue space for every envelope: a dropped chunk would leave the Mac with a corrupt transfer.
    // If the session goes away mid-transfer, stop; the Mac's assembler times the partial transfer out.
    private suspend fun sendImage(data: ByteArray) {
        val transferId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val started = TransportHub.sendAwait(newEnvelope {
            setClipboardBlobStart(
                ClipboardBlobStart.newBuilder()
                    .setTransferId(transferId)
                    .setMimeType("image/png")
                    .setTotalSize(data.size.toLong())
                    .setCopiedAtMs(now)
            )
        })
        if (!started) return
        for ((offset, chunk) in ClipboardChunker.chunks(data)) {
            val sent = TransportHub.sendAwait(newEnvelope {
                setClipboardBlobChunk(
                    ClipboardBlobChunk.newBuilder()
                        .setTransferId(transferId)
                        .setOffset(offset)
                        .setData(com.google.protobuf.ByteString.copyFrom(chunk))
                )
            })
            if (!sent) {
                Log.w(TAG, "clipboard image transfer interrupted at offset $offset")
                Diagnostics.recorder.onClipboardInterrupted()
                return
            }
        }
        if (!TransportHub.sendAwait(newEnvelope {
                setClipboardBlobEnd(ClipboardBlobEnd.newBuilder().setTransferId(transferId))
            })) {
            Diagnostics.recorder.onClipboardInterrupted()
            return
        }
        noteSent(sha256(data))
        Diagnostics.recorder.onClipboardSent()
    }

    // Each hash only suppresses the echo of the latest transfer in its direction. Once the other side
    // changes the clipboard, re-copying earlier content must sync again.
    private fun noteSent(hash: String) {
        lastSentHash = hash
        lastAppliedHash = null
    }

    private fun noteApplied(hash: String) {
        lastAppliedHash = hash
        lastSentHash = null
    }

    private fun handleIncoming(envelope: Envelope) {
        if (!isEnabled()) return
        assembler.expireIfNeeded()
        when {
            envelope.hasClipboardText() -> {
                val text = envelope.clipboardText.text
                if (text.isEmpty()) return
                noteApplied(sha256(text.toByteArray()))
                applyText(text)
                Diagnostics.recorder.onClipboardReceived()
            }
            envelope.hasClipboardBlobStart() -> {
                val start = envelope.clipboardBlobStart
                if (!assembler.start(start.transferId, start.mimeType, start.totalSize)) {
                    Log.w(TAG, "rejected clipboard blob mime=${start.mimeType} bytes=${start.totalSize}")
                    Diagnostics.recorder.onClipboardRejected()
                }
            }
            envelope.hasClipboardBlobChunk() -> {
                val chunk = envelope.clipboardBlobChunk
                val wasActive = assembler.activeTransferId == chunk.transferId
                val result = assembler.append(chunk.transferId, chunk.offset, chunk.data.toByteArray())
                // Count a transfer once, when its first bad chunk cancels it.
                if (result == ClipboardAssembler.ChunkResult.REJECTED && wasActive) {
                    Diagnostics.recorder.onClipboardRejected()
                }
            }
            envelope.hasClipboardBlobEnd() -> {
                val end = envelope.clipboardBlobEnd
                val wasActive = assembler.activeTransferId == end.transferId
                val data = assembler.end(end.transferId)
                if (data == null) {
                    if (wasActive) Diagnostics.recorder.onClipboardRejected()
                    return
                }
                val png = normalizeToPng(data, "image/png")
                if (png == null) {
                    Log.w(TAG, "failed to normalize incoming clipboard image id=${end.transferId}")
                    Diagnostics.recorder.onClipboardRejected()
                    return
                }
                noteApplied(sha256(png))
                applyImage(end.transferId, png)
                Diagnostics.recorder.onClipboardReceived()
            }
        }
    }

    private fun applyText(text: String) {
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        } catch (error: Exception) {
            Log.w(TAG, "failed to apply clipboard text", error)
        }
    }

    private fun applyImage(transferId: String, data: ByteArray) {
        try {
            val dir = File(context.cacheDir, CLIPBOARD_DIR).apply { mkdirs() }
            // The transfer ID comes from the Mac; only use it in a file name when it is a plain token.
            val fileId = transferId.takeIf { SAFE_TRANSFER_ID.matches(it) } ?: UUID.randomUUID().toString()
            val file = File(dir, "clipboard-$fileId.png").apply { writeBytes(data) }
            val uri = Uri.parse("content://${context.packageName}.$PROVIDER_SUFFIX/${file.name}")
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, CLIP_LABEL, uri))
            val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
            dir.listFiles()?.forEach { candidate ->
                if (candidate != file && candidate.isFile && candidate.lastModified() < cutoff) {
                    candidate.delete()
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "failed to apply clipboard image", error)
        }
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private fun sendPhoneStatus() {
        val metered = isMetered()
        TransportHub.send(newEnvelope {
            setPhoneStatus(PhoneStatus.newBuilder().setMetered(metered))
        })
    }

    private fun isPng(data: ByteArray): Boolean =
        data.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { data[it] == PNG_SIGNATURE[it] }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "DroidsyncClipboard"
        const val CLIP_LABEL = "Droidtosh"
        const val CLIPBOARD_DIR = "clipboard"
        const val PROVIDER_SUFFIX = "clipboard"
        const val STALE_FILE_AGE_MS = 60L * 60L * 1000L
        val SAFE_TRANSFER_ID = Regex("[A-Za-z0-9-]{1,64}")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
