package dev.southstar21.droidtosh.transport

internal class FakeDuplexStream(frames: List<ByteArray> = emptyList()) : DuplexStream {
    private val incoming = ArrayDeque(frames)
    val written = mutableListOf<ByteArray>()

    override fun readFrameOrNull(): ByteArray? =
        if (incoming.isEmpty()) null else incoming.removeFirst()

    override fun writeFrame(payload: ByteArray) {
        written += payload
    }

    override fun close() = Unit
}
