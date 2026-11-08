package dev.southstar21.droidtosh.push

object PushMessageRouter {
    fun handle(data: Map<String, String>, wake: () -> Unit): Boolean {
        if (data["type"] != "mac_online") return false
        wake()
        return true
    }
}
