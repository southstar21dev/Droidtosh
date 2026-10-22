package dev.southstar21.droidtosh.files

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Envelope.PayloadCase
import dev.southstar21.droidtosh.protocol.v1.FileAccept
import dev.southstar21.droidtosh.protocol.v1.FileCancel
import dev.southstar21.droidtosh.protocol.v1.FileChunk
import dev.southstar21.droidtosh.protocol.v1.FileComplete
import dev.southstar21.droidtosh.protocol.v1.FileEnd
import dev.southstar21.droidtosh.protocol.v1.FileOffer
import dev.southstar21.droidtosh.protocol.v1.FileProgress
import dev.southstar21.droidtosh.protocol.v1.FileReject
import dev.southstar21.droidtosh.transport.newEnvelope
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** A file to send. [open] returns a stream positioned at the given offset. */
interface FileSource {
    val name: String
    val mimeType: String
    val size: Long
    val modifiedAtMs: Long
    fun open(offset: Long): InputStream
    /** The size now, checked when a transfer (re)starts; a change fails it as "source_changed". */
    fun currentSize(): Long = size
    fun close() {}
}

data class SavedFile(val displayName: String, val location: String)

/** Where received files go: partials while they arrive, then the final destination. */
interface IncomingFileStore {
    fun partialFile(transferId: String): File
    fun usableSpace(): Long
    /** Moves a verified [partial] into place under [name]; throws on failure. */
    fun commit(partial: File, name: String, mimeType: String): SavedFile
    /** Deletes partials not in [keep] (left over from a previous process). */
    fun deleteOrphans(keep: Set<File>)
}

enum class TransferDirection { OUTGOING, INCOMING }

enum class TransferStatus {
    /** A folder is being zipped before it's queued (see [FileTransferEngine.beginPreparing]). */
    COMPRESSING,
    QUEUED,
    WAITING,
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TransferInfo(
    val id: String,
    val direction: TransferDirection,
    val name: String,
    val size: Long,
    val transferred: Long,
    val status: TransferStatus,
    val error: String? = null,
    val saved: SavedFile? = null,
) {
    val finished: Boolean
        get() = status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELLED
}

/**
 * File transfer in both directions (protocol/PROTOCOL-v2.md §7). Pure Kotlin: the transport, clock
 * and storage are injected. Callers drive it with [onEnvelope], [onConnected]/[onDisconnected],
 * [pump] (sends at most one envelope per call) and [tick] (stall and retention timers).
 *
 * Thread-safe; all public calls are serialized on the instance.
 */
class FileTransferEngine(
    private val send: (Envelope) -> Boolean,
    private val store: IncomingFileStore,
    private val receiveEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: Listener = object : Listener {},
    private val chunkSize: Int = CHUNK_SIZE,
    private val windowBytes: Long = WINDOW_BYTES,
    private val progressEveryBytes: Long = PROGRESS_EVERY_BYTES,
    private val maxFileSize: Long = MAX_FILE_SIZE,
    private val stallMs: Long = STALL_MS,
    private val retentionMs: Long = RETENTION_MS,
    private val maxIncoming: Int = MAX_INCOMING,
    private val keepFinished: Int = KEEP_FINISHED,
) {
    interface Listener {
        fun onFinished(info: TransferInfo) {}
        fun onResumed(info: TransferInfo) {}
    }

    private enum class OutState { QUEUED, AWAITING_ACCEPT, STREAMING, AWAITING_COMPLETE }

    private class Outgoing(val id: String, val source: FileSource) {
        var state = OutState.QUEUED
        var offerPending = true
        var position = 0L
        var received = 0L
        var hashedUpTo = 0L
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var sha256: ByteArray? = null
        var stream: InputStream? = null
        var streamPosition = -1L
        var pendingChunk: Envelope? = null
        var pendingChunkEnd = 0L
        var lastActivity = 0L
        var resumed = false
        var sourceClosed = false
    }

    private class Incoming(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val partial: File,
    ) {
        var out: FileOutputStream? = null
        var written = 0L
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var progressSent = 0L
        var lastActivity = 0L
    }

    private val queue = ArrayDeque<Outgoing>()
    // Outgoing transfers whose file is still being made, by id: their names.
    private val preparing = LinkedHashMap<String, String>()
    private val incoming = LinkedHashMap<String, Incoming>()
    private val finished = ArrayDeque<TransferInfo>()
    // Replies to re-sent Ends of transfers that already finished, so a retransmission gets the same answer.
    private val completions = LinkedHashMap<String, Envelope>()
    private var connected = false
    // Connected to a Mac that didn't negotiate `files`: nothing outgoing can be sent this session.
    private var unsupported = false

    private val mutableTransfers = MutableStateFlow<List<TransferInfo>>(emptyList())
    val transfers: StateFlow<List<TransferInfo>> = mutableTransfers.asStateFlow()

    /**
     * Deletes partials a previous process left behind, keeping those of transfers in progress. It
     * touches storage, so the owner calls it off the main thread rather than on construction.
     */
    @Synchronized
    fun deleteOrphanedPartials() {
        store.deleteOrphans(incoming.values.mapTo(HashSet()) { it.partial })
    }

    // Sending

    /** Queues [source] for sending; returns its transfer id. */
    @Synchronized
    fun enqueue(source: FileSource): String = enqueue(source, UUID.randomUUID().toString())

    /**
     * Lists an outgoing transfer named [name] as [TransferStatus.COMPRESSING] while its file is made
     * (a folder zipped); returns its id for [finishPreparing] or [failPreparing]. [cancel] works on it.
     */
    @Synchronized
    fun beginPreparing(name: String): String {
        val id = UUID.randomUUID().toString()
        preparing[id] = name
        publish()
        return id
    }

    /** False once the transfer [id] stopped preparing (cancelled): stop making its file. */
    @Synchronized
    fun isPreparing(id: String): Boolean = id in preparing

    /**
     * Queues the prepared [source] under [id]; returns false, closing [source], when the transfer was
     * cancelled meanwhile.
     */
    @Synchronized
    fun finishPreparing(id: String, source: FileSource): Boolean {
        if (preparing.remove(id) == null) {
            source.close()
            publish()
            return false
        }
        enqueue(source, id)
        return true
    }

    /** Fails a transfer whose file couldn't be made, with [reason] as its error. */
    @Synchronized
    fun failPreparing(id: String, reason: String) {
        val name = preparing.remove(id) ?: return
        finish(TransferInfo(id, TransferDirection.OUTGOING, name, 0, 0, TransferStatus.FAILED, reason))
        publish()
    }

    private fun enqueue(source: FileSource, id: String): String {
        val transfer = Outgoing(id, source)
        transfer.lastActivity = clock()
        if (source.size !in 0..maxFileSize) {
            source.close()
            finish(info(transfer, TransferStatus.FAILED, "too_large"))
        } else if (unsupported) {
            source.close()
            finish(info(transfer, TransferStatus.FAILED, NOT_SUPPORTED))
        } else {
            queue.addLast(transfer)
        }
        publish()
        return transfer.id
    }

    /**
     * Sends at most one envelope for the active outgoing transfer. Returns true if it did, so callers
     * loop until false and then wait for an inbound envelope, a connection change or a short delay.
     */
    @Synchronized
    fun pump(): Boolean {
        if (!connected) return false
        val transfer = queue.firstOrNull() ?: return false
        return when (transfer.state) {
            OutState.QUEUED, OutState.AWAITING_ACCEPT -> sendOffer(transfer)
            OutState.STREAMING -> sendNext(transfer)
            OutState.AWAITING_COMPLETE -> false
        }
    }

    private fun sendOffer(transfer: Outgoing): Boolean {
        if (!transfer.offerPending) return false
        val source = transfer.source
        val sent = send(newEnvelope {
            setFileOffer(
                FileOffer.newBuilder()
                    .setTransferId(transfer.id)
                    .setName(source.name)
                    .setMimeType(source.mimeType)
                    .setSize(source.size)
                    .setModifiedAtMs(source.modifiedAtMs)
            )
        })
        if (!sent) return false
        transfer.offerPending = false
        transfer.state = OutState.AWAITING_ACCEPT
        transfer.lastActivity = clock()
        publish()
        return true
    }

    private fun sendNext(transfer: Outgoing): Boolean {
        transfer.pendingChunk?.let { chunk ->
            if (!send(chunk)) return false
            transfer.pendingChunk = null
            transfer.position = transfer.pendingChunkEnd
            publish()
            return true
        }
        val size = transfer.source.size
        if (transfer.position >= size) return sendEnd(transfer)
        if (transfer.position - transfer.received >= windowBytes) return false
        val length = minOf(chunkSize.toLong(), size - transfer.position, windowBytes - (transfer.position - transfer.received)).toInt()
        val data = try {
            read(transfer, length)
        } catch (error: IOException) {
            fail(transfer, "read_failed", notifyPeer = true)
            return true
        }
        val offset = transfer.position
        val chunk = newEnvelope {
            setFileChunk(
                FileChunk.newBuilder()
                    .setTransferId(transfer.id)
                    .setOffset(offset)
                    .setData(ByteString.copyFrom(data))
            )
        }
        val end = offset + data.size
        if (send(chunk)) {
            transfer.position = end
            publish()
        } else {
            transfer.pendingChunk = chunk
            transfer.pendingChunkEnd = end
        }
        return true
    }

    private fun read(transfer: Outgoing, length: Int): ByteArray {
        if (transfer.stream == null || transfer.streamPosition != transfer.position) {
            runCatching { transfer.stream?.close() }
            transfer.stream = transfer.source.open(transfer.position)
            transfer.streamPosition = transfer.position
        }
        val stream = transfer.stream!!
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = stream.read(buffer, filled, length - filled)
            if (n < 0) throw IOException("source ended at ${transfer.position + filled} of ${transfer.source.size}")
            filled += n
        }
        val start = transfer.position
        // The digest covers [0, hashedUpTo); bytes re-read after a rewind are already in it.
        if (start <= transfer.hashedUpTo && start + length > transfer.hashedUpTo) {
            val skip = (transfer.hashedUpTo - start).toInt()
            transfer.digest.update(buffer, skip, length - skip)
            transfer.hashedUpTo = start + length
        }
        transfer.streamPosition += length
        return buffer
    }

    private fun sendEnd(transfer: Outgoing): Boolean {
        val sha = transfer.sha256 ?: transfer.digest.digest().also { transfer.sha256 = it }
        val sent = send(newEnvelope {
            setFileEnd(FileEnd.newBuilder().setTransferId(transfer.id).setSha256(ByteString.copyFrom(sha)))
        })
        if (!sent) return false
        transfer.state = OutState.AWAITING_COMPLETE
        transfer.lastActivity = clock()
        // The source stays open: a receiver missing bytes ignores End and the next session resumes.
        publish()
        return true
    }

    private fun handleAccept(accept: FileAccept) {
        val transfer = outgoing(accept.transferId) ?: return
        // Only the answer to an outstanding offer counts; the receiver answers every repeated offer.
        if (transfer.state != OutState.AWAITING_ACCEPT) return
        if (transfer.source.currentSize() != transfer.source.size) {
            fail(transfer, "source_changed", notifyPeer = true)
            return
        }
        // The digest covers exactly what was read so far, so never resume past it.
        val offset = minOf(accept.resumeOffset, transfer.hashedUpTo, transfer.source.size)
        if (offset > 0 && !transfer.resumed) {
            transfer.resumed = true
            listener.onResumed(info(transfer, TransferStatus.ACTIVE))
        }
        transfer.state = OutState.STREAMING
        transfer.offerPending = false
        transfer.position = offset
        transfer.received = offset
        transfer.pendingChunk = null
        transfer.lastActivity = clock()
        publish()
    }

    private fun handleProgress(progress: FileProgress) {
        val transfer = outgoing(progress.transferId) ?: return
        if (progress.received > transfer.received) transfer.received = minOf(progress.received, transfer.position)
        transfer.lastActivity = clock()
    }

    private fun handleReject(reject: FileReject) {
        val transfer = outgoing(reject.transferId) ?: return
        fail(transfer, reject.reason.ifEmpty { "rejected" }, notifyPeer = false)
    }

    private fun handleComplete(complete: FileComplete) {
        val transfer = outgoing(complete.transferId) ?: return
        queue.remove(transfer)
        closeSource(transfer)
        if (complete.ok) {
            finish(info(transfer, TransferStatus.COMPLETED).copy(
                transferred = transfer.source.size,
                saved = SavedFile(complete.savedAs, ""),
            ))
        } else {
            finish(info(transfer, TransferStatus.FAILED, complete.error.ifEmpty { "failed" }))
        }
        publish()
    }

    private fun fail(transfer: Outgoing, reason: String, notifyPeer: Boolean) {
        if (notifyPeer && transfer.state != OutState.QUEUED) sendCancel(transfer.id, "error")
        queue.remove(transfer)
        closeSource(transfer)
        finish(info(transfer, TransferStatus.FAILED, reason))
        publish()
    }

    private fun closeSource(transfer: Outgoing) {
        runCatching { transfer.stream?.close() }
        transfer.stream = null
        transfer.streamPosition = -1
        if (!transfer.sourceClosed) {
            transfer.sourceClosed = true
            runCatching { transfer.source.close() }
        }
    }

    private fun outgoing(id: String): Outgoing? = queue.firstOrNull { it.id == id && it.state != OutState.QUEUED }

    // Receiving

    private fun handleOffer(offer: FileOffer) {
        val id = offer.transferId
        completions[id]?.let {
            send(it)
            return
        }
        incoming[id]?.let { existing ->
            if (existing.size == offer.size) {
                // A re-offer (or a retransmitted offer) of a transfer in progress is a resume request.
                if (existing.written > 0) listener.onResumed(info(existing, TransferStatus.ACTIVE))
                send(accept(id, existing.written))
                return
            }
            // The file changed size since the partial was started: begin again.
            incoming.remove(id)
            runCatching { existing.out?.close() }
            existing.partial.delete()
        }
        val reason = when {
            !TRANSFER_ID.matches(id) || offer.size < 0 -> "invalid"
            !receiveEnabled() -> "disabled"
            offer.size > maxFileSize -> "too_large"
            incoming.size >= maxIncoming -> "busy"
            // Room for the partial plus its copy into place (MediaStore can't adopt the partial).
            store.usableSpace() < offer.size * 2 -> "disk_full"
            else -> null
        }
        val name = FileNames.sanitize(offer.name)
        if (reason != null) {
            send(newEnvelope { setFileReject(FileReject.newBuilder().setTransferId(id).setReason(reason)) })
            finish(TransferInfo(id, TransferDirection.INCOMING, name, offer.size, 0, TransferStatus.FAILED, reason))
            publish()
            return
        }
        val transfer = Incoming(id, name, offer.mimeType, offer.size, store.partialFile(id))
        try {
            transfer.partial.parentFile?.mkdirs()
            transfer.out = FileOutputStream(transfer.partial, false)
        } catch (error: IOException) {
            send(newEnvelope { setFileReject(FileReject.newBuilder().setTransferId(id).setReason("write_failed")) })
            finish(TransferInfo(id, TransferDirection.INCOMING, name, offer.size, 0, TransferStatus.FAILED, "write_failed"))
            publish()
            return
        }
        transfer.lastActivity = clock()
        incoming[id] = transfer
        send(accept(id, 0))
        publish()
    }

    private fun accept(id: String, offset: Long) = newEnvelope {
        setFileAccept(FileAccept.newBuilder().setTransferId(id).setResumeOffset(offset))
    }

    private fun handleChunk(chunk: FileChunk) {
        val transfer = incoming[chunk.transferId] ?: return
        val offset = chunk.offset
        val data = chunk.data
        // Earlier bytes are a duplicate; a gap or an overrun means the stream is broken.
        if (offset < transfer.written) return
        if (offset > transfer.written || transfer.written + data.size() > transfer.size) {
            abortIncoming(transfer, "error")
            return
        }
        transfer.lastActivity = clock()
        val length = data.size()
        try {
            val out = transfer.out ?: FileOutputStream(transfer.partial, true).also { transfer.out = it }
            val bytes = data.toByteArray()
            out.write(bytes)
            transfer.digest.update(bytes)
        } catch (error: IOException) {
            if (isOutOfSpace(error) || store.usableSpace() < transfer.size - transfer.written) {
                abortIncoming(transfer, "disk_full")
            } else {
                failIncoming(transfer, "write_failed")
            }
            return
        }
        transfer.written += length
        if (transfer.written - transfer.progressSent >= progressEveryBytes || transfer.written == transfer.size) {
            transfer.progressSent = transfer.written
            send(newEnvelope {
                setFileProgress(FileProgress.newBuilder().setTransferId(transfer.id).setReceived(transfer.written))
            })
        }
        publish()
    }

    private fun handleEnd(end: FileEnd) {
        val id = end.transferId
        completions[id]?.let { send(it); return }
        val transfer = incoming[id]
        if (transfer == null) {
            send(complete(id, ok = false, error = "unknown_transfer"))
            return
        }
        // Bytes are still missing (lost with a dropped session); the sender re-offers on reconnect.
        if (transfer.written < transfer.size) return
        incoming.remove(id)
        runCatching { transfer.out?.close() }
        transfer.out = null
        val sha = transfer.digest.digest()
        val reply: Envelope
        if (!sha.contentEquals(end.sha256.toByteArray())) {
            transfer.partial.delete()
            reply = complete(id, ok = false, error = "checksum_mismatch")
            finish(info(transfer, TransferStatus.FAILED, "checksum_mismatch"))
        } else {
            val saved = try {
                store.commit(transfer.partial, transfer.name, transfer.mimeType)
            } catch (error: Exception) {
                null
            }
            transfer.partial.delete()
            if (saved == null) {
                reply = complete(id, ok = false, error = "write_failed")
                finish(info(transfer, TransferStatus.FAILED, "write_failed"))
            } else {
                reply = complete(id, ok = true, savedAs = saved.displayName)
                finish(info(transfer, TransferStatus.COMPLETED).copy(saved = saved))
            }
        }
        remember(id, reply)
        send(reply)
        publish()
    }

    private fun complete(id: String, ok: Boolean, error: String = "", savedAs: String = "") = newEnvelope {
        setFileComplete(
            FileComplete.newBuilder().setTransferId(id).setOk(ok).setError(error).setSavedAs(savedAs)
        )
    }

    private fun remember(id: String, reply: Envelope) {
        completions[id] = reply
        while (completions.size > KEEP_COMPLETIONS) completions.remove(completions.keys.first())
    }

    /** A write failed: the transfer ends with a failed FileComplete (§7.3). */
    private fun failIncoming(transfer: Incoming, error: String) {
        incoming.remove(transfer.id)
        runCatching { transfer.out?.close() }
        transfer.partial.delete()
        val reply = complete(transfer.id, ok = false, error = error)
        remember(transfer.id, reply)
        send(reply)
        finish(info(transfer, TransferStatus.FAILED, error))
        publish()
    }

    private fun isOutOfSpace(error: IOException): Boolean =
        error.message.orEmpty().let { "ENOSPC" in it || "No space left" in it }

    private fun abortIncoming(transfer: Incoming, reason: String) {
        incoming.remove(transfer.id)
        runCatching { transfer.out?.close() }
        transfer.partial.delete()
        sendCancel(transfer.id, reason)
        finish(info(transfer, TransferStatus.FAILED, reason))
        publish()
    }

    // Both directions

    @Synchronized
    fun onEnvelope(envelope: Envelope) {
        when (envelope.payloadCase) {
            PayloadCase.FILE_OFFER -> handleOffer(envelope.fileOffer)
            PayloadCase.FILE_ACCEPT -> handleAccept(envelope.fileAccept)
            PayloadCase.FILE_REJECT -> handleReject(envelope.fileReject)
            PayloadCase.FILE_CHUNK -> handleChunk(envelope.fileChunk)
            PayloadCase.FILE_PROGRESS -> handleProgress(envelope.fileProgress)
            PayloadCase.FILE_END -> handleEnd(envelope.fileEnd)
            PayloadCase.FILE_COMPLETE -> handleComplete(envelope.fileComplete)
            PayloadCase.FILE_CANCEL -> handleCancel(envelope.fileCancel)
            else -> Unit
        }
    }

    private fun handleCancel(cancel: FileCancel) {
        val reason = cancel.reason.ifEmpty { "cancelled" }
        incoming.remove(cancel.transferId)?.let { transfer ->
            runCatching { transfer.out?.close() }
            transfer.partial.delete()
            finish(info(transfer, TransferStatus.CANCELLED, reason))
        }
        queue.firstOrNull { it.id == cancel.transferId }?.let { transfer ->
            queue.remove(transfer)
            closeSource(transfer)
            finish(info(transfer, TransferStatus.CANCELLED, reason))
        }
        publish()
    }

    /** Cancels a transfer the user no longer wants, in either direction. */
    @Synchronized
    fun cancel(id: String) {
        preparing.remove(id)?.let { name ->
            finish(TransferInfo(id, TransferDirection.OUTGOING, name, 0, 0, TransferStatus.CANCELLED, "user"))
        }
        incoming.remove(id)?.let { transfer ->
            runCatching { transfer.out?.close() }
            transfer.partial.delete()
            sendCancel(id, "user")
            finish(info(transfer, TransferStatus.CANCELLED, "user"))
        }
        queue.firstOrNull { it.id == id }?.let { transfer ->
            if (transfer.state != OutState.QUEUED) sendCancel(id, "user")
            queue.remove(transfer)
            closeSource(transfer)
            finish(info(transfer, TransferStatus.CANCELLED, "user"))
        }
        publish()
    }

    private fun sendCancel(id: String, reason: String) {
        send(newEnvelope { setFileCancel(FileCancel.newBuilder().setTransferId(id).setReason(reason)) })
    }

    /** A session (with the `files` capability) is ready: re-offer the unfinished outgoing transfer (§7.4). */
    @Synchronized
    fun onConnected() {
        connected = true
        unsupported = false
        val now = clock()
        queue.firstOrNull()?.let { transfer ->
            // Also after End: the receiver may have ignored it with bytes missing, or its Complete was lost.
            if (transfer.state != OutState.QUEUED) {
                transfer.state = OutState.AWAITING_ACCEPT
                transfer.offerPending = true
                transfer.pendingChunk = null
                transfer.lastActivity = now
            }
        }
        publish()
    }

    @Synchronized
    fun onDisconnected() {
        connected = false
        unsupported = false
        queue.firstOrNull()?.let { it.pendingChunk = null }
        publish()
    }

    /**
     * A session without the `files` capability: the Mac can't take files, so outgoing transfers
     * (queued now or while it lasts) fail instead of waiting forever.
     */
    @Synchronized
    fun onConnectedWithoutFiles() {
        connected = false
        unsupported = true
        val pending = queue.toList()
        queue.clear()
        pending.forEach { transfer ->
            closeSource(transfer)
            finish(info(transfer, TransferStatus.FAILED, NOT_SUPPORTED))
        }
        publish()
    }

    /** Drops partials 10 minutes after their last chunk and marks stalled sends; call every second or so. */
    @Synchronized
    fun tick() {
        val now = clock()
        val expired = incoming.values.filter { now - it.lastActivity > retentionMs }
        expired.forEach { transfer ->
            incoming.remove(transfer.id)
            runCatching { transfer.out?.close() }
            transfer.partial.delete()
            if (connected) sendCancel(transfer.id, "timeout")
            finish(info(transfer, TransferStatus.FAILED, "timeout"))
        }
        if (expired.isNotEmpty()) publish()
        // A stalled send waits for the next reconnect (§7.2); the status shows it as waiting.
        val active = queue.firstOrNull()
        if (active != null && active.state == OutState.STREAMING && now - active.lastActivity > stallMs) publish()
    }

    @Synchronized
    fun clearFinished() {
        finished.clear()
        publish()
    }

    // State

    private fun finish(info: TransferInfo) {
        finished.addFirst(info)
        while (finished.size > keepFinished) finished.removeLast()
        listener.onFinished(info)
    }

    private fun info(transfer: Outgoing, status: TransferStatus, error: String? = null) = TransferInfo(
        id = transfer.id,
        direction = TransferDirection.OUTGOING,
        name = transfer.source.name,
        size = transfer.source.size,
        transferred = transfer.received,
        status = status,
        error = error,
    )

    private fun info(transfer: Incoming, status: TransferStatus, error: String? = null) = TransferInfo(
        id = transfer.id,
        direction = TransferDirection.INCOMING,
        name = transfer.name,
        size = transfer.size,
        transferred = transfer.written,
        status = status,
        error = error,
    )

    private fun publish() {
        val now = clock()
        val active = queue.mapIndexed { index, transfer ->
            val status = when {
                index > 0 || transfer.state == OutState.QUEUED -> TransferStatus.QUEUED
                !connected -> TransferStatus.WAITING
                transfer.state == OutState.STREAMING && now - transfer.lastActivity > stallMs -> TransferStatus.WAITING
                else -> TransferStatus.ACTIVE
            }
            info(transfer, status)
        } + incoming.values.map { transfer ->
            info(transfer, if (connected) TransferStatus.ACTIVE else TransferStatus.WAITING)
        }
        val compressing = preparing.map { (id, name) ->
            TransferInfo(id, TransferDirection.OUTGOING, name, 0, 0, TransferStatus.COMPRESSING)
        }
        mutableTransfers.value = compressing + active + finished
    }

    companion object {
        const val CHUNK_SIZE = 256 * 1024
        const val WINDOW_BYTES = 4L * 1024 * 1024
        const val PROGRESS_EVERY_BYTES = 1L * 1024 * 1024
        const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024
        const val STALL_MS = 30_000L
        const val RETENTION_MS = 10 * 60_000L
        const val MAX_INCOMING = 8
        const val KEEP_FINISHED = 20
        const val KEEP_COMPLETIONS = 64

        /** The error of an outgoing transfer the connected Mac can't receive. */
        const val NOT_SUPPORTED = "not_supported"

        // Transfer ids name partial files, so only plain identifiers are accepted (UUIDs in practice).
        private val TRANSFER_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
