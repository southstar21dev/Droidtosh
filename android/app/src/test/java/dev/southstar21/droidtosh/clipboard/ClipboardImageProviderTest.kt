package dev.southstar21.droidtosh.clipboard

import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImageProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = "${context.packageName}.clipboard"
    private val provider = ClipboardImageProvider()
    private lateinit var clipboardDir: File

    @Before
    fun setUp() {
        clipboardDir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
    }

    @Test
    fun servesPngMimeType() {
        assertEquals("image/png", provider.getType(uri("a.png")))
    }

    @Test
    fun queryReportsDisplayNameAndSize() {
        File(clipboardDir, "a.png").writeBytes(ByteArray(7) { 1 })

        val cursor = provider.query(uri("a.png"), null, null, null, null)!!

        cursor.moveToFirst()
        assertEquals(
            "a.png",
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
        )
        assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
    }

    @Test
    fun queryProjectsRequestedColumns() {
        File(clipboardDir, "a.png").writeBytes(ByteArray(3) { 1 })

        val cursor = provider.query(
            uri("a.png"),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )!!

        assertEquals(1, cursor.columnCount)
        cursor.moveToFirst()
        assertEquals("a.png", cursor.getString(0))
    }

    @Test
    fun queryReturnsNullForMissingFile() {
        assertNull(provider.query(uri("missing.png"), null, null, null, null))
    }

    @Test
    fun openFileServesClipboardFileReadOnly() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        File(clipboardDir, "a.png").writeBytes(bytes)

        val descriptor = provider.openFile(uri("a.png"), "r")

        val read = FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        assertArrayEquals(bytes, read)
        descriptor.close()
    }

    @Test
    fun openFileRejectsTraversal() {
        File(context.cacheDir, "secret.png").writeBytes(byteArrayOf(1))

        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri("../secret.png"), "r")
        }
    }

    @Test
    fun queryReturnsNullForNestedPath() {
        File(clipboardDir, "b.png").writeBytes(byteArrayOf(1))

        assertNull(provider.query(nestedUri("nested", "b.png"), null, null, null, null))
    }

    @Test
    fun openFileRejectsNestedPath() {
        File(clipboardDir, "b.png").writeBytes(byteArrayOf(1))

        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(nestedUri("nested", "b.png"), "r")
        }
    }

    @Test
    fun openFileRejectsMalformedName() {
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri("bad\u0000name.png"), "r")
        }
    }

    @Test
    fun openFileRejectsWriteMode() {
        File(clipboardDir, "a.png").writeBytes(byteArrayOf(1))

        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(uri("a.png"), "w")
        }
    }

    @Test
    fun mutationsAreNoOps() {
        assertNull(provider.insert(uri("a.png"), ContentValues()))
        assertEquals(0, provider.update(uri("a.png"), ContentValues(), null, null))
        assertEquals(0, provider.delete(uri("a.png"), null, null))
    }

    private fun uri(name: String): Uri =
        Uri.Builder().scheme("content").authority(authority).appendPath(name).build()

    private fun nestedUri(vararg segments: String): Uri =
        Uri.Builder().scheme("content").authority(authority).apply {
            segments.forEach { appendPath(it) }
        }.build()
}
