package dev.southstar21.droidtosh.transport

import java.security.MessageDigest

/**
 * Remembers which relays refused the join line's push token (protocol/PROTOCOL-v2.md section 1.2.1):
 * a relay from before UnifiedPush caps the token field at 256 bytes and answers a longer
 * `up:<endpoint>` with `ERROR bad-join`. Such a relay is joined without the token until the app
 * version or the token changes, so a new phone still connects to an old relay.
 */
class RelayTokenRefusals(private val store: Store, private val appVersion: String) {
    /** Where the marks live; the app backs it with SharedPreferences. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** True when [relayKey] already refused this very [token] under this app version. */
    fun refused(relayKey: String, token: String?): Boolean =
        !token.isNullOrEmpty() && store.get(storeKey(relayKey)) == marker(token)

    fun remember(relayKey: String, token: String) {
        store.put(storeKey(relayKey), marker(token))
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
