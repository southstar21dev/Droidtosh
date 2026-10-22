package dev.southstar21.droidtosh.device

import android.app.NotificationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dev.southstar21.droidtosh.protocol.v1.ChargeSource
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import kotlin.math.roundToInt

/**
 * Framework values → [PhoneStatusSnapshot] fields. Plain functions over ints and booleans (the
 * framework constants are compile-time constants), so they run in JVM unit tests.
 */
object DeviceStatusMapping {
    /** Battery percent from ACTION_BATTERY_CHANGED's level and scale; -1 when unknown. */
    fun batteryPercent(level: Int, scale: Int): Int =
        if (level < 0 || scale <= 0) -1 else (level * 100f / scale).roundToInt().coerceIn(0, 100)

    /** Charging, or full while still plugged in. */
    fun charging(status: Int, plugged: Int): Boolean =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0)

    fun chargeSource(plugged: Int): ChargeSource = when {
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargeSource.CHARGE_SOURCE_WIRELESS
        plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> ChargeSource.CHARGE_SOURCE_DOCK
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargeSource.CHARGE_SOURCE_AC
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargeSource.CHARGE_SOURCE_USB
        else -> ChargeSource.CHARGE_SOURCE_UNSPECIFIED
    }

    /** The transports of the default network that matter here. */
    data class Transports(
        val wifi: Boolean = false,
        val cellular: Boolean = false,
        val ethernet: Boolean = false,
    )

    /**
     * [transports] null means there is no default network. A VPN reports its underlying transport
     * too; one with none of these (Bluetooth tethering, a VPN without underlying info) is "other".
     */
    fun networkType(transports: Transports?): NetworkType = when {
        transports == null -> NetworkType.NETWORK_TYPE_NONE
        transports.wifi -> NetworkType.NETWORK_TYPE_WIFI
        transports.ethernet -> NetworkType.NETWORK_TYPE_ETHERNET
        transports.cellular -> NetworkType.NETWORK_TYPE_CELLULAR
        else -> NetworkType.NETWORK_TYPE_OTHER
    }

    /** A signal level on a 0..[maxLevel] scale (WifiManager.calculateSignalLevel) as 0–4 bars. */
    fun bars(level: Int, maxLevel: Int): Int {
        if (level < 0 || maxLevel <= 0) return -1
        return (level * 4f / maxLevel).roundToInt().coerceIn(0, 4)
    }

    /**
     * Wi-Fi RSSI (dBm) as 0–4 bars, the way the framework did before levels became configurable:
     * -100 dBm or less is 0, -55 dBm or more is 4, linear in between. -1 for an invalid RSSI.
     */
    fun wifiBarsFromRssi(rssi: Int): Int = when {
        rssi == INVALID_RSSI || rssi >= 0 -> -1
        rssi <= MIN_RSSI -> 0
        rssi >= MAX_RSSI -> 4
        else -> ((rssi - MIN_RSSI) * 4f / (MAX_RSSI - MIN_RSSI)).toInt().coerceIn(0, 3)
    }

    /** "5G", "LTE", "3G", "2G" or empty, from the display info's network and override types. */
    fun cellularGeneration(networkType: Int, overrideNetworkType: Int): String {
        when (overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> return "LTE"
        }
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            else -> ""
        }
    }

    fun ringerMode(mode: Int): RingerMode = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> RingerMode.RINGER_MODE_NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.RINGER_MODE_VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerMode.RINGER_MODE_SILENT
        else -> RingerMode.RINGER_MODE_UNSPECIFIED
    }

    /** Any interruption filter other than "all" (or unknown) means Do Not Disturb is on. */
    fun doNotDisturb(interruptionFilter: Int): Boolean =
        interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    private const val INVALID_RSSI = -127
    private const val MIN_RSSI = -100
    private const val MAX_RSSI = -55
}
