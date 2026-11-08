package dev.southstar21.droidtosh.push

import dev.southstar21.droidtosh.BuildConfig
import dev.southstar21.droidtosh.data.CredentialStore
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.transport.ConnectionService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class DroidtoshMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!BuildConfig.FCM_ENABLED) return
        Diagnostics.recorder.onPush("new FCM token")
        PushTokenStore(applicationContext).set(token)
        ensureServiceRunning()
        if (BuildConfig.FCM_ENABLED && CredentialStore(applicationContext).isPaired()) {
            // A rotated token only reaches the relay in a fresh join line, so
            // bounce the service to reconnect and re-register it immediately.
            runCatching { ConnectionService.restart(applicationContext) }
        }
        ConnectionService.wakeForMacOnline()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.FCM_ENABLED) return
        // Only the message type: data values could be anything the relay chose to send.
        // A downgraded message can't start a foreground service unless battery optimization is off for us.
        val downgraded = message.priority != message.originalPriority
        Diagnostics.recorder.onPush(
            "push received (${message.data["type"] ?: "untyped"}${if (downgraded) ", priority downgraded" else ""})"
        )
        ensureServiceRunning()
        PushMessageRouter.handle(message.data) { ConnectionService.wakeForMacOnline() }
    }

    // Android 12+ permits foreground-service starts from a high-priority FCM data
    // message, which is what the relay sends for mac_online. Starting the service
    // resurrects it when Android killed it while the process was empty.
    private fun ensureServiceRunning() {
        if (!BuildConfig.FCM_ENABLED) return
        if (!CredentialStore(applicationContext).isPaired()) return
        runCatching { ConnectionService.start(applicationContext) }
    }
}
