package dev.southstar21.droidtosh.transport

import java.security.MessageDigest

/**
 * Remembers which relays refused the join line's push token (protocol/PROTOCOL-v2.md section 1.2.1):
 * a relay from before UnifiedPush caps the token field at 256 bytes and answers a longer
 * `up:<endpoint>` with `ERROR bad-join`. Such a relay is joined without the token until the app
 * version or the token changes, or a day has passed (the relay may have been updated meanwhile),
 * so a new phone still connects to an old relay.
 */
class RelayTokenRefusals(
    private val store: Store,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where the marks live; the app backs it with SharedPreferences. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** True when [relayKey] refused this very [token] under this app version within [RETRY_AFTER_MS]. */
    fun refused(relayKey: String, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val stored = store.get(storeKey(relayKey)) ?: return false
        val prefix = marker(token) + "|"
        if (!stored.startsWith(prefix)) return false
        val at = stored.removePrefix(prefix).toLongOrNull() ?: return false
        return clock() - at in 0 until RETRY_AFTER_MS
    }

    fun remember(relayKey: String, token: String) {
        store.put(storeKey(relayKey), marker(token) + "|" + clock())
    }

    fun forget(relayKey: String) {
        store.remove(storeKey(relayKey))
    }

    /** The token to put in the join line: null while this relay is known to refuse it. */
    fun tokenFor(relayKey: String, token: String?): String? =
        token?.takeIf { it.isNotEmpty() && !refused(relayKey, it) }

    // The token is a capability URL, so only a hash of it is kept.
    private fun marker(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return appVersion + "|" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun storeKey(relayKey: String) = "relay_refused:$relayKey"

    companion object {
        /** How long a refusal holds before the token is tried again, in case the relay was updated. */
        const val RETRY_AFTER_MS = 24 * 60 * 60_000L

        /** The relay as the user paired it: address and pinned certificate. */
        fun relayKey(host: String?, port: Int?, relayFingerprint: String?): String =
            "$host:$port|$relayFingerprint"

        /**
         * Whether to retry a join without the token: the relay said `bad-join` to a join that
         * carried one. Any other failure is not about the token.
         */
        fun shouldRetryWithoutToken(sentToken: Boolean, result: RelayConnectResult): Boolean =
            sentToken && result is RelayConnectResult.Failed &&
                (result.cause as? RelayErrorException)?.reason == "bad-join"
    }
}
