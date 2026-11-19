package dev.southstar21.droidtosh.background

import dev.southstar21.droidtosh.protocol.v1.BackgroundReason

/** One entry of the "Background" checklist: something that can stop Droidtosh while the phone is idle. */
enum class BackgroundCheck { NotificationAccess, PostNotifications, BatteryOptimization, VendorBattery }

/** What the checklist is computed from; plain values so the logic runs in JVM tests. */
data class BackgroundInputs(
    val notificationAccess: Boolean,
    val postNotifications: Boolean,
    /** Android's own battery optimization is off for Droidtosh. */
    val batteryIgnored: Boolean,
    /** The phone's manufacturer when it ships its own battery manager; null otherwise. */
    val vendor: OemVendor? = null,
    /** The user went through the manufacturer's settings (it can't be read back, so it is remembered). */
    val vendorHandled: Boolean = false,
)

/**
 * The single source for what is wrong in the background, used by the Connection page's checklist,
 * Home's attention line and the status the phone reports to the Mac (protocol section 8.3).
 */
object BackgroundHealth {
    /** Every applicable check, in the order they are listed; the vendor one only on a known OEM. */
    fun applicable(inputs: BackgroundInputs): List<BackgroundCheck> = buildList {
        add(BackgroundCheck.NotificationAccess)
        add(BackgroundCheck.PostNotifications)
        add(BackgroundCheck.BatteryOptimization)
        if (inputs.vendor != null) add(BackgroundCheck.VendorBattery)
    }

    fun isOk(check: BackgroundCheck, inputs: BackgroundInputs): Boolean = when (check) {
        BackgroundCheck.NotificationAccess -> inputs.notificationAccess
        BackgroundCheck.PostNotifications -> inputs.postNotifications
        BackgroundCheck.BatteryOptimization -> inputs.batteryIgnored
        BackgroundCheck.VendorBattery -> inputs.vendor == null || inputs.vendorHandled
    }

    fun failing(inputs: BackgroundInputs): List<BackgroundCheck> =
        applicable(inputs).filterNot { isOk(it, inputs) }

    /**
     * What the Mac is told: only the checks that can cut the connection while idle (the two battery
     * ones); missing notification permissions don't, and the phone shows those itself.
     */
    fun reason(inputs: BackgroundInputs): BackgroundReason = when {
        !isOk(BackgroundCheck.BatteryOptimization, inputs) -> BackgroundReason.BACKGROUND_REASON_BATTERY_OPTIMIZED
        !isOk(BackgroundCheck.VendorBattery, inputs) -> BackgroundReason.BACKGROUND_REASON_VENDOR_BATTERY
        else -> BackgroundReason.BACKGROUND_REASON_UNSPECIFIED
    }

    fun restricted(inputs: BackgroundInputs): Boolean =
        reason(inputs) != BackgroundReason.BACKGROUND_REASON_UNSPECIFIED
}
