package dev.southstar21.droidtosh.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class ClipboardImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("clipboard images are read-only")
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolve(uri: Uri): File? {
        if (uri.pathSegments.size != 1) return null
        val name = uri.lastPathSegment ?: return null
        if (name.isEmpty() || name.contains('/')) return null
        val dir = File(context?.cacheDir ?: return null, CLIPBOARD_DIR)
        return runCatching {
            val canonicalDir = dir.canonicalFile
            val file = File(canonicalDir, name).canonicalFile
            if (file.parentFile != canonicalDir || !file.isFile) null else file
        }.getOrNull()
    }

    private companion object {
        const val MIME_TYPE = "image/png"
        const val CLIPBOARD_DIR = "clipboard"
    }
}
