package dev.southstar21.droidtosh.background

import dev.southstar21.droidtosh.protocol.v1.BackgroundReason
import dev.southstar21.droidtosh.ui.Attention
import dev.southstar21.droidtosh.ui.DroidUiState
import dev.southstar21.droidtosh.ui.Issue
import dev.southstar21.droidtosh.ui.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHealthTest {
    private val healthy = BackgroundInputs(
        notificationAccess = true,
        postNotifications = true,
        batteryIgnored = true,
    )

    @Test
    fun healthyPhoneHasNothingFailing() {
        assertEquals(emptyList<BackgroundCheck>(), BackgroundHealth.failing(healthy))
        assertFalse(BackgroundHealth.restricted(healthy))
        assertEquals(BackgroundReason.BACKGROUND_REASON_UNSPECIFIED, BackgroundHealth.reason(healthy))
    }

    @Test
    fun vendorCheckOnlyExistsOnAKnownVendor() {
        assertFalse(BackgroundCheck.VendorBattery in BackgroundHealth.applicable(healthy))
        val samsung = healthy.copy(vendor = OemVendor.Samsung)
        assertTrue(BackgroundCheck.VendorBattery in BackgroundHealth.applicable(samsung))
        assertEquals(listOf(BackgroundCheck.VendorBattery), BackgroundHealth.failing(samsung))
        assertEquals(emptyList<BackgroundCheck>(), BackgroundHealth.failing(samsung.copy(vendorHandled = true)))
    }

    @Test
    fun batteryOptimizationWinsOverTheVendorReason() {
        val both = healthy.copy(batteryIgnored = false, vendor = OemVendor.Xiaomi)
        assertEquals(BackgroundReason.BACKGROUND_REASON_BATTERY_OPTIMIZED, BackgroundHealth.reason(both))
        assertEquals(
            BackgroundReason.BACKGROUND_REASON_VENDOR_BATTERY,
            BackgroundHealth.reason(healthy.copy(vendor = OemVendor.Xiaomi)),
        )
    }

    @Test
    fun missingNotificationPermissionsFailTheChecklistButAreNotReportedToTheMac() {
        val inputs = healthy.copy(notificationAccess = false, postNotifications = false)
        assertEquals(
            listOf(BackgroundCheck.NotificationAccess, BackgroundCheck.PostNotifications),
            BackgroundHealth.failing(inputs),
        )
        assertFalse(BackgroundHealth.restricted(inputs))
    }

    @Test
    fun homeAttentionUsesTheSameChecklist() {
        val ui = DroidUiState(
            permissions = PermissionStatus(notifications = true, phone = true, notificationAccess = true),
            batteryOptimizationIgnored = true,
            oemVendor = OemVendor.Oppo,
            vendorBatteryHandled = false,
        )
        assertEquals(listOf<Attention>(Attention.Problem(Issue.VendorBattery)), ui.attentions)
        assertEquals(
            emptyList<Attention>(),
            ui.copy(vendorBatteryHandled = true).attentions,
        )
    }

    @Test
    fun detectsVendorsByManufacturerOrBrand() {
        assertEquals(OemVendor.Samsung, OemVendor.detect("samsung"))
        assertEquals(OemVendor.Xiaomi, OemVendor.detect("Xiaomi", "POCO"))
        assertEquals(OemVendor.Xiaomi, OemVendor.detect("Unknown", "Redmi"))
        assertEquals(OemVendor.Oppo, OemVendor.detect("OPPO"))
        assertEquals(OemVendor.Oppo, OemVendor.detect("realme"))
        assertEquals(OemVendor.Huawei, OemVendor.detect("HONOR"))
        assertEquals(OemVendor.Vivo, OemVendor.detect("vivo", "iQOO"))
        assertEquals(OemVendor.OnePlus, OemVendor.detect("OnePlus"))
        assertNull(OemVendor.detect("Google", "google"))
        assertNull(OemVendor.detect(null))
    }

    @Test
    fun everyVendorHasAFallbackGuide() {
        OemVendor.entries.forEach {
            assertTrue(it.dontKillMyAppUrl.startsWith("https://dontkillmyapp.com/"))
            assertTrue(it.components.isNotEmpty())
        }
    }
}
