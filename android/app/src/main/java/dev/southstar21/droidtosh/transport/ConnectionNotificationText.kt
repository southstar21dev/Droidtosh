package dev.southstar21.droidtosh.transport

/** Title and body of the ongoing connection notification. */
data class NotificationContent(val title: String, val text: String)

object ConnectionNotificationText {
    const val APP_TITLE = "Droidtosh"

    /**
     * Maps a connection state to the ongoing notification. [statusText] is the loop's plain status, used
     * whenever the state has no more specific wording. [macName] is the last Mac this phone connected to,
     * or empty when unknown.
     */
    fun forState(state: ConnectionState, statusText: String?, macName: String = ""): NotificationContent? {
        if (state is ConnectionState.Disconnected) {
            when (state.cause) {
                DisconnectCause.MacAsleepOrOffline -> {
                    val title = if (macName.isBlank()) "Mac is asleep" else "$macName is asleep"
                    return NotificationContent(title, "Connects when it wakes up or Droidtosh opens")
                }
                DisconnectCause.RelayUnreachable ->
                    return NotificationContent("Relay unreachable", "Check the internet connection")
                DisconnectCause.NoWifiLocal ->
                    return NotificationContent("No Wi-Fi", "Local mode resumes when Wi-Fi is back")
                DisconnectCause.PairingExpired ->
                    return NotificationContent("Pairing expired", "Scan the pairing code on the Mac again")
                else -> Unit
            }
        }
        return statusText?.let { NotificationContent(APP_TITLE, it) }
    }
}
