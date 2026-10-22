package dev.southstar21.droidtosh.transport

/** How the phone reaches the Mac (protocol/PROTOCOL-v2.md §1.5). */
enum class ConnectionMode(val wire: String) {
    RELAY("relay"),
    LOCAL("local"),

    /** The relay first; the local network when the relay answers WAIT or fails. */
    BOTH("both");

    val usesRelay: Boolean get() = this != LOCAL
    val usesLocal: Boolean get() = this != RELAY

    companion object {
        fun fromWire(value: String): ConnectionMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Which path the current session runs over. */
enum class SessionPath(val label: String) {
    RELAY("relay"),
    LOCAL("local"),
}

/** A direct address for the Mac on the local network (§1.4). */
data class LocalEndpoint(val host: String, val port: Int) {
    companion object {
        const val DEFAULT_PORT = 7444
    }
}
