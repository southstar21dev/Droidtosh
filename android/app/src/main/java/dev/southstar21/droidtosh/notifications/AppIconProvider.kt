package dev.southstar21.droidtosh.notifications

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream

class AppIconProvider(private val packageManager: PackageManager) {
    fun iconPng(
        packageName: String,
        sizePx: Int = 96,
        maxBytes: Int = 256 * 1024,
    ): ByteArray? = runCatching {
        val drawable = packageManager.getApplicationIcon(packageName)
        var dimension = sizePx
        var png = renderPng(drawable, dimension)
        while (png.size > maxBytes && dimension > 1) {
            dimension /= 2
            png = renderPng(drawable, dimension)
        }
        png.takeIf { it.size <= maxBytes }
    }.getOrNull()

    private fun renderPng(drawable: Drawable, dimension: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, dimension, dimension)
        drawable.draw(canvas)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
