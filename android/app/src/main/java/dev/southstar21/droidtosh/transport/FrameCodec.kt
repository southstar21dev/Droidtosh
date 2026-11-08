package dev.southstar21.droidtosh.transport

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

object FrameCodec {
    const val MAX_FRAME_SIZE = 1_048_576

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 4)
        out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }
}

class FrameReader(private val source: InputStream) {

    fun readFrameOrNull(): ByteArray? {
        val header = readFully(4) ?: return null
        val length = ByteBuffer.wrap(header).int
        require(length in 0..FrameCodec.MAX_FRAME_SIZE) { "frame too large: $length" }
        return readFully(length) ?: throw EOFException("truncated frame")
    }

    private fun readFully(length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = source.read(buffer, offset, length - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw EOFException("truncated frame")
            }
            offset += read
        }
        return buffer
    }
}
