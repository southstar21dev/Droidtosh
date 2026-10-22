package dev.southstar21.droidtosh.device

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * The phone's status from framework callbacks, cached so [snapshot] never blocks: the sticky battery
 * broadcast, power save, the default network callback, a [TelephonyCallback] on the data SIM (only
 * with READ_PHONE_STATE, registered late via [onPermissionsChanged] if granted later), airplane mode, ringer and DND broadcasts. Nothing polls; [onChanged] runs
 * whenever a cached value changes. Registered from [start] to [stop] (while the connection service runs).
 */
class AndroidDeviceStatusSource(
    private val context: Context,
    private val onChanged: () -> Unit,
) : DeviceStatusSource {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val executor = Executor { it.run() }

    @Volatile
    private var current = PhoneStatusSnapshot()

    @Volatile
    private var defaultNetwork: Network? = null

    private var started = false
    private var baseTelephony: TelephonyManager? = null
    private var subscriptionWatcher: TelephonyCallback? = null
    private var telephony: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> update { battery(it, intent) }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> update { it.copy(powerSave = power.isPowerSaveMode) }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> update { it.copy(airplaneMode = airplaneMode()) }
                AudioManager.RINGER_MODE_CHANGED_ACTION ->
                    update { it.copy(ringerMode = DeviceStatusMapping.ringerMode(audio.ringerMode)) }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> update {
                    it.copy(doNotDisturb = DeviceStatusMapping.doNotDisturb(notifications.currentInterruptionFilter))
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            defaultNetwork = network
            update { network(it, capabilities) }
        }

        override fun onLost(network: Network) {
            // A switch announces the new default first; only the loss of the current one means none.
            if (network != defaultNetwork) return
            defaultNetwork = null
            update { it.copy(networkType = DeviceStatusMapping.networkType(null), wifiLevel = -1) }
        }
    }

    override fun snapshot(): PhoneStatusSnapshot = current

    @Synchronized
    fun start() {
        if (started) return
        started = true
        current = current.copy(
            powerSave = power.isPowerSaveMode,
            networkType = DeviceStatusMapping.networkType(null),
            airplaneMode = airplaneMode(),
            ringerMode = DeviceStatusMapping.ringerMode(audio.ringerMode),
            doNotDisturb = DeviceStatusMapping.doNotDisturb(notifications.currentInterruptionFilter),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        // All protected system broadcasts, which only the system can send; every handler re-reads the
        // real state anyway. Like the call tracker, export so delivery doesn't depend on how the
        // platform treats system broadcasts to unexported receivers.
        runCatching {
            // ACTION_BATTERY_CHANGED is sticky: registering returns the current battery state.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                ?.let { sticky -> current = battery(current, sticky) }
        }.onFailure { Log.w(TAG, "can't register status receiver", it) }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "can't register network callback", it) }
        registerTelephony()
        onChanged()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        unregisterTelephony()
    }

    /**
     * Registers the telephony callbacks if READ_PHONE_STATE was granted since [start] (the user
     * granted it while the service ran); a no-op when they're registered or the permission is missing.
     */
    fun onPermissionsChanged() = registerTelephony()

    /**
     * Follows the active data SIM: a watcher on the default manager reports its subscription, and the
     * signal and display-info callback is (re)registered on that subscription. Needs READ_PHONE_STATE;
     * without it the cellular fields stay unknown.
     */
    @Synchronized
    private fun registerTelephony() {
        if (!started || subscriptionWatcher != null) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val base = context.getSystemService(TelephonyManager::class.java)
        if (!granted || base == null) {
            clearCellular()
            return
        }
        val watcher = SubscriptionWatcher()
        try {
            base.registerTelephonyCallback(executor, watcher)
        } catch (error: Exception) {
            Log.w(TAG, "can't watch the data subscription", error)
            clearCellular()
            return
        }
        subscriptionWatcher = watcher
        baseTelephony = base
        onDataSubscription(SubscriptionManager.getActiveDataSubscriptionId())
    }

    @Synchronized
    private fun onDataSubscription(subscription: Int) {
        if (!started) return
        if (subscription == subscriptionId && telephonyCallback != null) return
        unregisterSubscription()
        val base = baseTelephony ?: return
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            clearCellular()
            return
        }
        val manager = base.createForSubscriptionId(subscription)
        val callback = Callback(manager)
        try {
            manager.registerTelephonyCallback(executor, callback)
        } catch (error: Exception) {
            Log.w(TAG, "can't register telephony callback", error)
            clearCellular()
            return
        }
        telephony = manager
        telephonyCallback = callback
        subscriptionId = subscription
        update { it.copy(carrier = carrier(manager), cellularLevel = cellularLevel(manager, manager.signalStrength)) }
    }

    private fun unregisterTelephony() {
        unregisterSubscription()
        subscriptionWatcher?.let { watcher -> runCatching { baseTelephony?.unregisterTelephonyCallback(watcher) } }
        subscriptionWatcher = null
        baseTelephony = null
    }

    private fun unregisterSubscription() {
        val manager = telephony
        val callback = telephonyCallback
        if (manager != null && callback != null) runCatching { manager.unregisterTelephonyCallback(callback) }
        telephony = null
        telephonyCallback = null
        subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun clearCellular() = update { it.copy(cellularLevel = -1, cellularGeneration = "", carrier = "") }

    private inner class SubscriptionWatcher :
        TelephonyCallback(),
        TelephonyCallback.ActiveDataSubscriptionIdListener {
        override fun onActiveDataSubscriptionIdChanged(subId: Int) = onDataSubscription(subId)
    }

    private inner class Callback(private val manager: TelephonyManager) :
        TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            update { it.copy(cellularLevel = cellularLevel(manager, signalStrength), carrier = carrier(manager)) }
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            val generation = DeviceStatusMapping.cellularGeneration(
                telephonyDisplayInfo.networkType,
                telephonyDisplayInfo.overrideNetworkType,
            )
            update { it.copy(cellularGeneration = generation, carrier = carrier(manager)) }
        }
    }

    private fun cellularLevel(manager: TelephonyManager, strength: SignalStrength?): Int {
        if (manager.simState != TelephonyManager.SIM_STATE_READY) return -1
        return strength?.level?.coerceIn(0, 4) ?: -1
    }

    private fun carrier(manager: TelephonyManager): String =
        runCatching { manager.networkOperatorName }.getOrNull().orEmpty()
            .ifEmpty { runCatching { manager.simOperatorName }.getOrNull().orEmpty() }

    private fun battery(base: PhoneStatusSnapshot, intent: Intent): PhoneStatusSnapshot {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return base.copy(
            batteryPercent = DeviceStatusMapping.batteryPercent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            ),
            charging = DeviceStatusMapping.charging(status, plugged),
            chargeSource = DeviceStatusMapping.chargeSource(plugged),
        )
    }

    private fun network(base: PhoneStatusSnapshot, capabilities: NetworkCapabilities): PhoneStatusSnapshot {
        val transports = DeviceStatusMapping.Transports(
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        )
        val type = DeviceStatusMapping.networkType(transports)
        val wifiLevel = if (transports.wifi) wifiLevel(capabilities) else -1
        return base.copy(networkType = type, wifiLevel = wifiLevel)
    }

    private fun wifiLevel(capabilities: NetworkCapabilities): Int {
        val rssi = (capabilities.transportInfo as? WifiInfo)?.rssi
            ?: capabilities.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            ?: return -1
        return runCatching { DeviceStatusMapping.bars(wifi.calculateSignalLevel(rssi), wifi.maxSignalLevel) }
            .getOrNull()?.takeIf { it >= 0 }
            ?: DeviceStatusMapping.wifiBarsFromRssi(rssi)
    }

    private fun airplaneMode(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** Applies [change] to the cache and reports it if anything the Mac shows changed. */
    private fun update(change: (PhoneStatusSnapshot) -> PhoneStatusSnapshot) {
        val changed = synchronized(this) {
            val next = change(current)
            (next != current).also { current = next }
        }
        if (changed) onChanged()
    }

    private companion object {
        const val TAG = "DroidsyncStatus"
    }
}
