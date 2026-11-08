package dev.southstar21.droidtosh.transport

/**
 * Logging seam for the transport layer, so the connection loop and hub also run on a plain JVM
 * (the end-to-end harness's phone peer) where android.util.Log isn't implemented.
 */
object TransportLog {
    fun interface Sink {
        fun log(priority: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { priority, tag, message, error ->
        when (priority) {
            'W' -> android.util.Log.w(tag, message, error)
            else -> android.util.Log.d(tag, message, error)
        }
    }

    fun d(tag: String, message: String, error: Throwable? = null) = sink.log('D', tag, message, error)

    fun w(tag: String, message: String, error: Throwable? = null) = sink.log('W', tag, message, error)
}
