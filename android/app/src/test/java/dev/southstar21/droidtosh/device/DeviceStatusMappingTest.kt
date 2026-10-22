package dev.southstar21.droidtosh.device

import android.app.NotificationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import dev.southstar21.droidtosh.device.DeviceStatusMapping.Transports
import dev.southstar21.droidtosh.protocol.v1.ChargeSource
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusMappingTest {
    @Test
    fun batteryPercent() {
        assertEquals(57, DeviceStatusMapping.batteryPercent(57, 100))
        assertEquals(50, DeviceStatusMapping.batteryPercent(128, 256))
        assertEquals(100, DeviceStatusMapping.batteryPercent(100, 100))
        assertEquals(-1, DeviceStatusMapping.batteryPercent(-1, 100))
        assertEquals(-1, DeviceStatusMapping.batteryPercent(50, 0))
    }

    @Test
    fun chargingIncludesFullWhilePluggedIn() {
        assertTrue(DeviceStatusMapping.charging(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(DeviceStatusMapping.charging(BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC))
        assertFalse(DeviceStatusMapping.charging(BatteryManager.BATTERY_STATUS_FULL, 0))
        assertFalse(DeviceStatusMapping.charging(BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        // Plugged in but not charging (e.g. a charge limit) is not charging.
        assertFalse(DeviceStatusMapping.charging(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_AC))
    }

    @Test
    fun chargeSource() {
        assertEquals(ChargeSource.CHARGE_SOURCE_AC, DeviceStatusMapping.chargeSource(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(ChargeSource.CHARGE_SOURCE_USB, DeviceStatusMapping.chargeSource(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(ChargeSource.CHARGE_SOURCE_WIRELESS, DeviceStatusMapping.chargeSource(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals(ChargeSource.CHARGE_SOURCE_DOCK, DeviceStatusMapping.chargeSource(BatteryManager.BATTERY_PLUGGED_DOCK))
        assertEquals(ChargeSource.CHARGE_SOURCE_UNSPECIFIED, DeviceStatusMapping.chargeSource(0))
    }

    @Test
    fun networkType() {
        assertEquals(NetworkType.NETWORK_TYPE_NONE, DeviceStatusMapping.networkType(null))
        assertEquals(NetworkType.NETWORK_TYPE_WIFI, DeviceStatusMapping.networkType(Transports(wifi = true)))
        assertEquals(NetworkType.NETWORK_TYPE_CELLULAR, DeviceStatusMapping.networkType(Transports(cellular = true)))
        assertEquals(NetworkType.NETWORK_TYPE_ETHERNET, DeviceStatusMapping.networkType(Transports(ethernet = true)))
        // A VPN over Wi-Fi reports both; Bluetooth tethering reports neither.
        assertEquals(NetworkType.NETWORK_TYPE_WIFI, DeviceStatusMapping.networkType(Transports(wifi = true, cellular = true)))
        assertEquals(NetworkType.NETWORK_TYPE_OTHER, DeviceStatusMapping.networkType(Transports()))
    }

    @Test
    fun levelsScaleToFourBars() {
        assertEquals(4, DeviceStatusMapping.bars(4, 4))
        assertEquals(0, DeviceStatusMapping.bars(0, 4))
        assertEquals(2, DeviceStatusMapping.bars(2, 4))
        // Some devices report more than five levels.
        assertEquals(4, DeviceStatusMapping.bars(6, 6))
        assertEquals(2, DeviceStatusMapping.bars(3, 6))
        assertEquals(-1, DeviceStatusMapping.bars(-1, 4))
        assertEquals(-1, DeviceStatusMapping.bars(2, 0))
    }

    @Test
    fun rssiToBars() {
        assertEquals(4, DeviceStatusMapping.wifiBarsFromRssi(-40))
        assertEquals(4, DeviceStatusMapping.wifiBarsFromRssi(-55))
        assertEquals(3, DeviceStatusMapping.wifiBarsFromRssi(-60))
        assertEquals(2, DeviceStatusMapping.wifiBarsFromRssi(-75))
        assertEquals(1, DeviceStatusMapping.wifiBarsFromRssi(-85))
        assertEquals(0, DeviceStatusMapping.wifiBarsFromRssi(-99))
        assertEquals(0, DeviceStatusMapping.wifiBarsFromRssi(-110))
        assertEquals(-1, DeviceStatusMapping.wifiBarsFromRssi(-127))
        assertEquals(-1, DeviceStatusMapping.wifiBarsFromRssi(0))
    }

    @Test
    fun cellularGeneration() {
        val none = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
        assertEquals("5G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_NR, none))
        assertEquals("5G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals("5G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED))
        assertEquals("LTE", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA))
        assertEquals("LTE", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_LTE, none))
        assertEquals("3G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_HSPAP, none))
        assertEquals("3G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_UMTS, none))
        assertEquals("2G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_EDGE, none))
        assertEquals("2G", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_GSM, none))
        assertEquals("", DeviceStatusMapping.cellularGeneration(TelephonyManager.NETWORK_TYPE_UNKNOWN, none))
    }

    @Test
    fun ringerAndDnd() {
        assertEquals(RingerMode.RINGER_MODE_NORMAL, DeviceStatusMapping.ringerMode(AudioManager.RINGER_MODE_NORMAL))
        assertEquals(RingerMode.RINGER_MODE_VIBRATE, DeviceStatusMapping.ringerMode(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(RingerMode.RINGER_MODE_SILENT, DeviceStatusMapping.ringerMode(AudioManager.RINGER_MODE_SILENT))
        assertEquals(RingerMode.RINGER_MODE_UNSPECIFIED, DeviceStatusMapping.ringerMode(42))

        assertFalse(DeviceStatusMapping.doNotDisturb(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(DeviceStatusMapping.doNotDisturb(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertTrue(DeviceStatusMapping.doNotDisturb(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(DeviceStatusMapping.doNotDisturb(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertTrue(DeviceStatusMapping.doNotDisturb(NotificationManager.INTERRUPTION_FILTER_NONE))
    }

    @Test
    fun fastFields() {
        val base = PhoneStatusSnapshot()
        assertFalse(base.copy(batteryPercent = 5, wifiLevel = 1, carrier = "x", powerSave = true).differsInFastFields(base))
        assertTrue(base.copy(charging = true).differsInFastFields(base))
        assertTrue(base.copy(doNotDisturb = true).differsInFastFields(base))
    }
}
