package dev.southstar21.droidtosh.clipboard

object ClipboardTransfer {
    const val CHUNK_SIZE = 256 * 1024
    const val MAX_BLOB_SIZE = 32 * 1024 * 1024
    const val MAX_TEXT_SIZE = 512 * 1024
    val ALLOWED_MIME = setOf("image/png", "image/jpeg")
}

object ClipboardChunker {
    fun chunks(data: ByteArray): List<Pair<Int, ByteArray>> {
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + ClipboardTransfer.CHUNK_SIZE, data.size)
            out += offset to data.copyOfRange(offset, end)
            offset = end
        }
        return out
    }
}

class ClipboardAssembler(
    private val maxBlobSize: Int = ClipboardTransfer.MAX_BLOB_SIZE,
    private val timeoutMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class ChunkResult { PROGRESS, COMPLETED, REJECTED }

    var activeTransferId: String? = null
        private set

    private var expectedSize = 0
    // Preallocated to the declared size and filled in place; appending with `+=` copied the whole
    // buffer per chunk (quadratic for a 32 MB image).
    private var buffer = ByteArray(0)
    private var filled = 0
    private var lastActivity = 0L

    fun start(transferId: String, mimeType: String, totalSize: Long): Boolean {
        cancel()
        if (mimeType !in ClipboardTransfer.ALLOWED_MIME || totalSize <= 0 || totalSize > maxBlobSize) return false
        activeTransferId = transferId
        expectedSize = totalSize.toInt()
        buffer = ByteArray(expectedSize)
        filled = 0
        lastActivity = clock()
        return true
    }

    fun append(transferId: String, offset: Int, data: ByteArray): ChunkResult {
        if (activeTransferId != transferId || offset != filled) {
            cancel()
            return ChunkResult.REJECTED
        }
        if (filled + data.size > expectedSize) {
            cancel()
            return ChunkResult.REJECTED
        }
        data.copyInto(buffer, destinationOffset = filled)
        filled += data.size
        lastActivity = clock()
        return if (filled == expectedSize) ChunkResult.COMPLETED else ChunkResult.PROGRESS
    }

    fun end(transferId: String): ByteArray? {
        if (activeTransferId != transferId || filled != expectedSize) {
            cancel()
            return null
        }
        val data = buffer
        cancel()
        return data
    }

    fun expireIfNeeded(): Boolean {
        if (activeTransferId == null || clock() - lastActivity <= timeoutMs) return false
        cancel()
        return true
    }

    fun cancel() {
        activeTransferId = null
        expectedSize = 0
        buffer = ByteArray(0)
        filled = 0
    }
}

object ClipboardPolicy {
    fun shouldAutoSend(isImage: Boolean, metered: Boolean, enabled: Boolean): Boolean =
        enabled && (!isImage || !metered)
}
