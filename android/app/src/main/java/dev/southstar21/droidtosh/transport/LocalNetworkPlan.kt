package dev.southstar21.droidtosh.transport

/**
 * What a connection attempt should try, given whether the phone is on a network that can reach a Mac
 * directly. Local mode has nothing to dial without Wi-Fi or Ethernet, so it neither connects nor
 * wakes up for backoff retries (and Bonjour discovery stays off) until such a network appears.
 */
object LocalNetworkPlan {
    data class Plan(
        val dialRelay: Boolean,
        val dialLocal: Boolean,
    ) {
        /** Neither path can work right now: wait for a Wi-Fi or Ethernet network instead of retrying. */
        val waitForLocalNetwork: Boolean get() = !dialRelay && !dialLocal
    }

    fun decide(mode: ConnectionMode, hasRelay: Boolean, localNetworkAvailable: Boolean): Plan = Plan(
        dialRelay = mode.usesRelay && hasRelay,
        // In `both` mode only the local candidates are skipped; the relay is still tried.
        dialLocal = mode.usesLocal && localNetworkAvailable,
    )
}

/**
 * Tracks the Wi-Fi and Ethernet networks that are up. Fed by [android.net.ConnectivityManager]
 * callbacks (see [LocalNetworkMonitor]); kept free of framework types so it runs in JVM tests.
 */
class LocalNetworkTracker {
    private val networks = HashSet<Any>()

    /** True while at least one Wi-Fi or Ethernet network is up. */
    @get:Synchronized
    val available: Boolean get() = networks.isNotEmpty()

    /** Records [network] as up; returns whether [available] changed. */
    @Synchronized
    fun onAvailable(network: Any): Boolean {
        val before = networks.isNotEmpty()
        networks.add(network)
        return !before
    }

    /** Records [network] as gone; returns whether [available] changed. */
    @Synchronized
    fun onLost(network: Any): Boolean {
        val before = networks.isNotEmpty()
        networks.remove(network)
        return before && networks.isEmpty()
    }

    @Synchronized
    fun reset(current: Collection<Any>) {
        networks.clear()
        networks.addAll(current)
    }
}
