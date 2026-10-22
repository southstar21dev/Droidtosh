package dev.southstar21.droidtosh.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTransferTest {
    @Test
    fun chunkerSplitsWithOffsets() {
        val data = ByteArray(ClipboardTransfer.CHUNK_SIZE * 2 + 10)
        val chunks = ClipboardChunker.chunks(data)
        assertEquals(
            listOf(0, ClipboardTransfer.CHUNK_SIZE, ClipboardTransfer.CHUNK_SIZE * 2),
            chunks.map { it.first },
        )
        assertEquals(listOf(ClipboardTransfer.CHUNK_SIZE, ClipboardTransfer.CHUNK_SIZE, 10), chunks.map { it.second.size })
    }

    @Test
    fun assemblerRoundTrip() {
        var now = 0L
        val assembler = ClipboardAssembler(clock = { now })
        val payload = ByteArray(600_000) { 3 }
        assertTrue(assembler.start("t1", "image/png", payload.size.toLong()))
        var completed = false
        for ((offset, chunk) in ClipboardChunker.chunks(payload)) {
            if (assembler.append("t1", offset, chunk) == ClipboardAssembler.ChunkResult.COMPLETED) completed = true
        }
        assertTrue(completed)
        assertTrue(payload.contentEquals(assembler.end("t1")))
        assertNull(assembler.activeTransferId)
    }

    @Test
    fun rejectsBadStartAndOffsetMismatch() {
        val assembler = ClipboardAssembler()
        assertFalse(assembler.start("t", "text/plain", 10))
        assertFalse(assembler.start("t", "image/png", 0))
        assertFalse(assembler.start("t", "image/png", ClipboardTransfer.MAX_BLOB_SIZE + 1L))
        assertTrue(assembler.start("t", "image/png", 20))
        assertEquals(ClipboardAssembler.ChunkResult.REJECTED, assembler.append("t", 5, ByteArray(5)))
        assertNull(assembler.activeTransferId)
    }

    @Test
    fun newStartReplacesAndExpiryCancels() {
        var now = 0L
        val assembler = ClipboardAssembler(clock = { now })
        assertTrue(assembler.start("a", "image/png", 10))
        assertTrue(assembler.start("b", "image/jpeg", 10))
        assertEquals("b", assembler.activeTransferId)
        now = 31_000
        assertTrue(assembler.expireIfNeeded())
        assertNull(assembler.activeTransferId)
    }

    @Test
    fun policyTable() {
        assertTrue(ClipboardPolicy.shouldAutoSend(isImage = false, metered = true, enabled = true))
        assertTrue(ClipboardPolicy.shouldAutoSend(isImage = true, metered = false, enabled = true))
        assertFalse(ClipboardPolicy.shouldAutoSend(isImage = true, metered = true, enabled = true))
        assertFalse(ClipboardPolicy.shouldAutoSend(isImage = false, metered = false, enabled = false))
    }
}
