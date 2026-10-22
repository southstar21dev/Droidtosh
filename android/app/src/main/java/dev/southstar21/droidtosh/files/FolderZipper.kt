package dev.southstar21.droidtosh.files

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One file or folder of a tree being zipped; a document tree in the app, plain files in tests. */
interface FolderNode {
    val name: String
    val isDirectory: Boolean
    val modifiedAtMs: Long
    fun children(): List<FolderNode>
    fun open(): InputStream
}

/** A [FolderNode] over the local file system. */
class LocalFolderNode(private val file: File) : FolderNode {
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val modifiedAtMs: Long get() = file.lastModified()
    override fun children(): List<FolderNode> = file.listFiles().orEmpty().sortedBy { it.name }.map(::LocalFolderNode)
    override fun open(): InputStream = file.inputStream()
}

/** The archive would pass the file size limit. */
class FolderTooLargeException : IOException("the zipped folder is over the size limit")

/** Zipping stopped because the transfer was cancelled. */
class FolderZipCancelledException : IOException("cancelled")

/**
 * Zips a folder so it can be sent as one file (§7 carries single files only), the way the Mac does:
 * `<folder>.zip` with the folder itself at its root, so unzipping recreates it with its relative
 * structure (empty folders included). It streams: nothing is held in memory beyond a buffer, and
 * zipping stops as soon as the archive grows past the limit.
 */
object FolderZipper {
    fun archiveName(folderName: String): String = "${entryName(folderName).ifEmpty { "folder" }}.zip"

    /**
     * Writes the archive of [root] to [out]. Throws [FolderTooLargeException] once it would exceed
     * [maxSize] and [FolderZipCancelledException] once [isCancelled] is true; [out] is deleted then.
     */
    fun zip(
        root: FolderNode,
        out: File,
        maxSize: Long = FileTransferEngine.MAX_FILE_SIZE,
        isCancelled: () -> Boolean = { false },
    ) {
        try {
            ZipOutputStream(LimitedOutputStream(out.outputStream().buffered(), maxSize)).use { zip ->
                add(zip, root, entryName(root.name).ifEmpty { "folder" }, isCancelled)
            }
        } catch (error: Exception) {
            out.delete()
            throw error
        }
    }

    private fun add(zip: ZipOutputStream, node: FolderNode, path: String, isCancelled: () -> Boolean) {
        if (isCancelled()) throw FolderZipCancelledException()
        if (node.isDirectory) {
            val children = node.children()
            // An empty folder needs its own entry; otherwise its files imply it.
            if (children.isEmpty()) {
                zip.putNextEntry(entry("$path/", node))
                zip.closeEntry()
            }
            val used = HashSet<String>()
            for (child in children) {
                val name = unique(entryName(child.name).ifEmpty { "file" }, used)
                add(zip, child, "$path/$name", isCancelled)
            }
            return
        }
        zip.putNextEntry(entry(path, node))
        node.open().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
                if (isCancelled()) throw FolderZipCancelledException()
            }
        }
        zip.closeEntry()
    }

    private fun entry(path: String, node: FolderNode) = ZipEntry(path).apply {
        if (node.modifiedAtMs > 0) time = node.modifiedAtMs
    }

    /** A path component safe inside a zip: no separators or control characters. */
    private fun entryName(name: String): String =
        name.filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim().let { if (it == "." || it == "..") "" else it }

    /** Two documents in one folder may share a display name; zip entries can't. */
    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while (true) {
            val candidate = "${name.substring(0, dot)} ($n)${name.substring(dot)}"
            if (used.add(candidate)) return candidate
            n += 1
        }
    }

    private class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        private var written = 0L

        override fun write(b: Int) {
            count(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count(len)
            out.write(b, off, len)
        }

        private fun count(n: Int) {
            written += n
            if (written > limit) throw FolderTooLargeException()
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}

/** A temporary archive sent as a file; [close] (once its transfer ends) deletes [cleanup]. */
class TemporaryFileSource(
    private val file: File,
    override val name: String,
    private val cleanup: File = file,
) : FileSource {
    override val mimeType: String = "application/zip"
    override val size: Long = file.length()
    override val modifiedAtMs: Long = file.lastModified()
    override fun open(offset: Long): InputStream = file.inputStream().also { it.channel.position(offset) }
    override fun currentSize(): Long = file.length()
    override fun close() {
        cleanup.deleteRecursively()
    }
}

/** Sends a folder through a [FileTransferEngine] as `<folder>.zip`. */
object FolderSender {
    /**
     * Lists the transfer as compressing, zips [root] into a new directory under [workDir] and queues
     * the archive under the same id; the archive is deleted when the transfer ends (or right away on
     * failure or cancel). A too-large archive fails the transfer as `zip_too_large`. Blocking (it
     * reads and compresses the whole tree): call it on an I/O thread. Returns the transfer id.
     * [onArchived] sees the finished archive just before it's queued.
     */
    fun send(
        engine: FileTransferEngine,
        root: FolderNode,
        workDir: File,
        maxSize: Long = FileTransferEngine.MAX_FILE_SIZE,
        onArchived: (id: String, archive: File) -> Unit = { _, _ -> },
    ): String {
        val name = FolderZipper.archiveName(root.name)
        val id = engine.beginPreparing(name)
        val dir = File(workDir, id)
        val archive = File(dir, name)
        try {
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("can't create $dir")
            FolderZipper.zip(root, archive, maxSize) { !engine.isPreparing(id) }
            onArchived(id, archive)
            engine.finishPreparing(id, TemporaryFileSource(archive, name, cleanup = dir))
        } catch (error: Exception) {
            dir.deleteRecursively()
            when (error) {
                is FolderZipCancelledException -> Unit
                is FolderTooLargeException -> engine.failPreparing(id, ZIP_TOO_LARGE)
                else -> engine.failPreparing(id, ZIP_FAILED)
            }
        }
        return id
    }

    const val ZIP_TOO_LARGE = "zip_too_large"
    const val ZIP_FAILED = "zip_failed"
}
