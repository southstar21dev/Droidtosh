package dev.southstar21.droidtosh.transport

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCodecTest {

    @Test
    fun encodeKnownVector() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 1, 2, 3),
            FrameCodec.encode(byteArrayOf(1, 2, 3))
        )
    }

    @Test
    fun decodeSingleFrame() {
        val reader = FrameReader(ByteArrayInputStream(FrameCodec.encode(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), reader.readFrameOrNull())
    }

    @Test
    fun decodeSplitAcrossChunks() {
        val frame = FrameCodec.encode(ByteArray(300) { 0x7F })
        class DripInputStream(private val data: ByteArray) : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < data.size) data[index++].toInt() and 0xFF else -1
        }
        val reader = FrameReader(DripInputStream(frame))
        assertArrayEquals(ByteArray(300) { 0x7F }, reader.readFrameOrNull())
    }

    @Test
    fun decodeMultipleFramesInOneChunk() {
        val stream = ByteArrayInputStream(FrameCodec.encode(byteArrayOf(1)) + FrameCodec.encode(byteArrayOf(2, 3)))
        val reader = FrameReader(stream)
        assertArrayEquals(byteArrayOf(1), reader.readFrameOrNull())
        assertArrayEquals(byteArrayOf(2, 3), reader.readFrameOrNull())
        assertArrayEquals(null, reader.readFrameOrNull())
    }

    @Test
    fun zeroLengthFrame() {
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        assertArrayEquals(ByteArray(0), reader.readFrameOrNull())
    }

    @Test
    fun oversizeFrameThrows() {
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
        assertThrows(IllegalArgumentException::class.java) { reader.readFrameOrNull() }
    }
}
