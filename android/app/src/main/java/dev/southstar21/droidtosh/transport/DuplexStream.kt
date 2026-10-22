package dev.southstar21.droidtosh.transport

import java.net.Socket

interface DuplexStream {
    fun readFrameOrNull(): ByteArray?
    fun writeFrame(payload: ByteArray)
    fun close()
}

class SocketDuplexStream(private val socket: Socket) : DuplexStream {
    private val reader = FrameReader(socket.getInputStream())
    private val output = socket.getOutputStream()

    override fun readFrameOrNull(): ByteArray? = reader.readFrameOrNull()

    override fun writeFrame(payload: ByteArray) {
        synchronized(output) {
            output.write(FrameCodec.encode(payload))
            output.flush()
        }
    }

    override fun close() {
        socket.close()
    }
}
