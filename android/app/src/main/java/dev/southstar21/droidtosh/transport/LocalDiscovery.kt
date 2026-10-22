package dev.southstar21.droidtosh.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds the paired Mac's `_droidtosh._tcp` Bonjour service (protocol/PROTOCOL-v2.md §1.4). Only services
 * whose TXT `fp` is a prefix of the paired Mac fingerprint are kept. [onFound] fires when a matching
 * service resolves, so a waiting connection loop can try it at once.
 */
class LocalDiscovery(context: Context, private val onFound: () -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val endpoints = ConcurrentHashMap<String, LocalEndpoint>()
    private val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

    @Volatile
    private var fingerprint: String? = null

    @Volatile
    private var listener: NsdManager.DiscoveryListener? = null

    @Volatile
    var startedAtMs: Long = 0L
        private set

    val isRunning: Boolean get() = listener != null

    /** The resolved addresses of the paired Mac, IPv4 first. */
    fun endpoints(): List<LocalEndpoint> =
        endpoints.values.sortedBy { if (it.host.contains(':')) 1 else 0 }

    @Synchronized
    fun start(macFingerprint: String) {
        if (listener != null || executor.isShutdown) return
        fingerprint = macFingerprint.lowercase()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                TransportLog.d(TAG, "discovery failed to start: $errorCode")
                synchronized(this@LocalDiscovery) { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                endpoints.remove(info.serviceName)
                unregisterInfoCallback(info.serviceName)
            }
        }
        listener = discovery
        startedAtMs = System.currentTimeMillis()
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure {
                TransportLog.d(TAG, "discovery unavailable: ${it.message}")
                listener = null
            }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        infoCallbacks.keys.toList().forEach(::unregisterInfoCallback)
        endpoints.clear()
    }

    /** Stops discovery for good and releases the resolver thread; the owner is going away. */
    fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (infoCallbacks.containsKey(info.serviceName)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    infoCallbacks.remove(info.serviceName)
                }
                override fun onServiceUpdated(updated: NsdServiceInfo) =
                    accept(updated, updated.hostAddresses)
                override fun onServiceLost() {
                    endpoints.remove(info.serviceName)
                }
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            infoCallbacks[info.serviceName] = callback
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { infoCallbacks.remove(info.serviceName) }
        } else {
            resolveLegacy(info)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo) {
        // Before API 34 only one resolve may run at a time; they're serialised on [executor].
        executor.execute {
            val done = java.util.concurrent.CountDownLatch(1)
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = done.countDown()
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        accept(resolved, listOfNotNull(resolved.host))
                        done.countDown()
                    }
                })
            }.onFailure { done.countDown() }
            done.await(RESOLVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun accept(info: NsdServiceInfo, addresses: List<InetAddress>) {
        val paired = fingerprint ?: return
        val advertised = info.attributes["fp"]?.toString(Charsets.UTF_8)?.lowercase()
        if (!matchesFingerprint(advertised, paired)) return
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
        val host = address.hostAddress ?: return
        val endpoint = LocalEndpoint(host, info.port)
        if (endpoints.put(info.serviceName, endpoint) != endpoint) {
            TransportLog.d(TAG, "found Mac at $host:${info.port}")
            onFound()
        }
    }

    private fun unregisterInfoCallback(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        infoCallbacks.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    companion object {
        const val SERVICE_TYPE = "_droidtosh._tcp"
        private const val TAG = "DroidsyncNsd"
        private const val RESOLVE_TIMEOUT_S = 5L
        /** The Mac advertises 16 hex characters; anything much shorter would match too easily. */
        private const val MIN_FP_PREFIX = 8

        fun matchesFingerprint(advertised: String?, paired: String): Boolean =
            advertised != null && advertised.length >= MIN_FP_PREFIX && paired.lowercase().startsWith(advertised)
    }
}
