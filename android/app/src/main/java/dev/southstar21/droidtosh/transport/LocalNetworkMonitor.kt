package dev.southstar21.droidtosh.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Watches for a Wi-Fi or Ethernet network, the only kind a Mac can be reached on directly. [onChange]
 * runs with the new availability whenever it flips. Registered from [start] to [stop] while the
 * connection service runs; nothing polls.
 */
class LocalNetworkMonitor(
    context: Context,
    private val onChange: (available: Boolean) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = LocalNetworkTracker()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Whether the phone is on Wi-Fi or Ethernet right now. */
    val available: Boolean get() = tracker.available

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()

    @Synchronized
    fun start() {
        if (callback != null) return
        // Seed from what is up now, so the first connection attempt doesn't see a false "no network"
        // before the callback's first delivery.
        @Suppress("DEPRECATION")
        tracker.reset(
            connectivity.allNetworks.filter { network ->
                // A VPN reports its underlying transports but never matches the request (NOT_VPN),
                // so it would get no onLost and keep the answer stuck at true.
                connectivity.getNetworkCapabilities(network)
                    ?.let { isLocal(it) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
            },
        )
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (tracker.onAvailable(network)) onChange(true)
            }

            override fun onLost(network: Network) {
                if (tracker.onLost(network)) onChange(false)
            }
        }
        callback = created
        runCatching { connectivity.registerNetworkCallback(request, created) }
            .onFailure {
                // Without a callback the answer can never change; assume a network rather than never dialling.
                callback = null
                tracker.onAvailable("unknown")
            }
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun isLocal(capabilities: NetworkCapabilities) =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
