package dev.southstar21.droidtosh.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun registerApplicationIcon() {
        shadowOf(context.packageManager).setApplicationIcon(
            context.packageName,
            context.packageManager.defaultActivityIcon,
        )
    }

    @Test
    fun producesPngForInstalledPackage() {
        val bytes = AppIconProvider(context.packageManager).iconPng(context.packageName)
        assertNotNull(bytes)
        val magic = bytes!!.take(4).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), magic)
    }

    @Test
    fun returnsNullForUnknownPackage() {
        assertNull(AppIconProvider(context.packageManager).iconPng("com.does.not.exist"))
    }
}
