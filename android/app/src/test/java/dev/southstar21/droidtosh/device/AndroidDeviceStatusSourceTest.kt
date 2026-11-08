package dev.southstar21.droidtosh.device

import android.Manifest
import android.app.Application
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeviceStatusSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun phonePermissionGrantedWhileRunningRegistersTelephony() {
        val subscription = 3
        ShadowSubscriptionManager.setActiveDataSubscriptionId(subscription)
        val base = app.getSystemService(TelephonyManager::class.java)
        val perSubscription = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(base).setTelephonyManagerForSubscriptionId(subscription, perSubscription)
        shadowOf(perSubscription).setNetworkOperatorName("Carrier")
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        var changes = 0
        val source = AndroidDeviceStatusSource(app) { changes += 1 }
        source.start()
        assertEquals("", source.snapshot().carrier)

        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val before = changes
        source.onPermissionsChanged()
        assertEquals("Carrier", source.snapshot().carrier)
        assertEquals(before + 1, changes)
        // Already registered: another foreground/permission result changes nothing.
        source.onPermissionsChanged()
        assertEquals(before + 1, changes)
        source.stop()
    }
}
