package dev.southstar21.droidtosh.push

import dev.southstar21.droidtosh.data.CredentialStore
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.transport.ConnectionService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives the distributor's callbacks; a push does exactly what an FCM data message does. */
class UnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Diagnostics.recorder.onPush("new UnifiedPush endpoint")
        val store = PushTokenStore(applicationContext)
        store.unifiedPushEndpoint = endpoint.url
        if (store.provider() != WakeProvider.UNIFIEDPUSH) return
        ensureServiceRunning()
        if (CredentialStore(applicationContext).isPaired()) {
            // The endpoint only reaches the relay in a fresh join line, so redial.
            runCatching { ConnectionService.reconnectNow(applicationContext) }
        }
        ConnectionService.wakeForMacOnline()
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (PushTokenStore(applicationContext).provider() != WakeProvider.UNIFIEDPUSH) return
        // Only the message type: the body is whatever the relay (or anyone with the endpoint) sent.
        val type = TYPE_PATTERN.find(String(message.content, Charsets.UTF_8).take(512))?.groupValues?.get(1)
        Diagnostics.recorder.onPush("UnifiedPush received (${type ?: "untyped"})")
        ensureServiceRunning()
        PushMessageRouter.handle(mapOf("type" to (type ?: ""))) { ConnectionService.wakeForMacOnline() }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Diagnostics.recorder.onPush("UnifiedPush registration failed ($reason)")
    }

    override fun onUnregistered(instance: String) {
        Diagnostics.recorder.onPush("UnifiedPush unregistered")
        PushTokenStore(applicationContext).unifiedPushEndpoint = null
        if (CredentialStore(applicationContext).isPaired()) {
            runCatching { ConnectionService.reconnectNow(applicationContext) }
        }
    }

    private fun ensureServiceRunning() {
        if (!CredentialStore(applicationContext).isPaired()) return
        runCatching { ConnectionService.start(applicationContext) }
    }

    private companion object {
        val TYPE_PATTERN = Regex("\"type\"\\s*:\\s*\"([A-Za-z0-9_]{1,32})\"")
    }
}
