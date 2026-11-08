package dev.southstar21.droidtosh.transport

object RelayJoinLine {
    private const val PREFIX = "DROIDSYNC/2"

    fun format(room: String, role: String, fcmToken: String? = null): String {
        if (!fcmToken.isNullOrEmpty()) {
            return "$PREFIX $room $role $fcmToken\n"
        }
        return "$PREFIX $room $role\n"
    }
}

sealed class RelayResponse {
    object Paired : RelayResponse()
    object Waiting : RelayResponse()
    data class Error(val reason: String) : RelayResponse()

    companion object {
        fun parse(line: String): RelayResponse {
            val trimmed = line.trim()
            return when {
                trimmed == "PAIRED" -> Paired
                trimmed == "WAIT" -> Waiting
                trimmed.startsWith("ERROR ") -> Error(trimmed.removePrefix("ERROR "))
                else -> throw IllegalArgumentException("malformed relay response: $line")
            }
        }
    }
}
