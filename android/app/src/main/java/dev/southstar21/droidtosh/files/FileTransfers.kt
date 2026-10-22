package dev.southstar21.droidtosh.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.transport.ConnectionService
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.ui.Destination
import dev.southstar21.droidtosh.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The app's file transfers: one [FileTransferEngine] for the process, run by the connection service
 * while it's up. Sending opens each file right away (share-sheet grants end with the activity), and
 * received files land in Download/Droidtosh/ through MediaStore.
 */
object FileTransfers {
    private const val TAG = "DroidsyncFiles"
    private const val CHANNEL_ID = "files"
    private const val PROGRESS_NOTIFICATION_ID = 40
    private const val PREFERENCES = "droidsync_files"
    private const val RECEIVE_KEY = "receive_enabled"

    @Volatile
    private var engine: FileTransferEngine? = null
    private var runner: FileTransferRunner? = null
    private var notifierJob: Job? = null
    private lateinit var appContext: Context
    // One at a time: the start-up sweep runs before any folder is zipped into the cache.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Synchronized
    fun engine(context: Context): FileTransferEngine {
        engine?.let { return it }
        appContext = context.applicationContext
        createChannel(appContext)
        val created = FileTransferEngine(
            send = TransportHub::send,
            store = MediaStoreDownloads(appContext),
            receiveEnabled = { receiveEnabled(appContext) },
            listener = Notifier,
        )
        engine = created
        runner = FileTransferRunner(created)
        // The first caller is often the UI on the main thread; the sweep lists and deletes files.
        ioScope.launch {
            created.deleteOrphanedPartials()
            // Folder archives of a previous process whose transfers never finished.
            folderWorkDir(appContext).deleteRecursively()
        }
        return created
    }

    fun transfers(context: Context): StateFlow<List<TransferInfo>> = engine(context).transfers

    /** Called by [ConnectionService] while it runs; transfers only move while it's up. */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        val engine = engine(context)
        runner?.start(scope)
        if (notifierJob?.isActive == true) return
        notifierJob = scope.launch {
            engine.transfers.sample(1_000).collect(::updateProgressNotification)
        }
    }

    @Synchronized
    fun stop() {
        runner?.stop()
        notifierJob?.cancel()
        notifierJob = null
        if (::appContext.isInitialized) {
            appContext.getSystemService(NotificationManager::class.java).cancel(PROGRESS_NOTIFICATION_ID)
        }
    }

    /**
     * Opens [uris] now and queues them for the Mac; returns how many were queued. Files whose size
     * can't be determined (pipes) are skipped.
     */
    fun send(context: Context, uris: List<Uri>): Int {
        val engine = engine(context)
        var queued = 0
        for (uri in uris) {
            val source = runCatching { UriSource.open(context.contentResolver, uri) }
                .onFailure { Log.w(TAG, "can't open $uri", it) }
                .getOrNull() ?: continue
            engine.enqueue(source)
            queued += 1
        }
        if (queued > 0) {
            ConnectionService.start(context)
            runner?.poke()
        }
        return queued
    }

    /**
     * Zips the document tree [treeUri] (from ACTION_OPEN_DOCUMENT_TREE) into the cache and queues
     * the archive for the Mac as `<folder>.zip`, listed as compressing meanwhile.
     */
    fun sendFolder(context: Context, treeUri: Uri) {
        val engine = engine(context)
        val appContext = context.applicationContext
        ConnectionService.start(appContext)
        ioScope.launch {
            val root = runCatching { DocumentTreeNode.root(appContext.contentResolver, treeUri) }
                .onFailure { Log.w(TAG, "can't open folder $treeUri", it) }
                .getOrNull() ?: return@launch
            FolderSender.send(engine, root, folderWorkDir(appContext))
            runner?.poke()
        }
    }

    private fun folderWorkDir(context: Context) = File(context.cacheDir, "outgoing-folders")

    fun cancel(context: Context, id: String) {
        engine(context).cancel(id)
        runner?.poke()
    }

    fun clearFinished(context: Context) = engine(context).clearFinished()

    fun receiveEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(RECEIVE_KEY, true)

    fun setReceiveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(RECEIVE_KEY, enabled).apply()
    }

    private object Notifier : FileTransferEngine.Listener {
        override fun onFinished(info: TransferInfo) {
            val diagnostics = Diagnostics.recorder
            when {
                info.status == TransferStatus.COMPLETED && info.direction == TransferDirection.OUTGOING ->
                    diagnostics.onFileSent()
                info.status == TransferStatus.COMPLETED -> diagnostics.onFileReceived()
                else -> diagnostics.onFileFailed("${info.direction.name.lowercase()} ${info.error ?: info.status.name.lowercase()}")
            }
            if (::appContext.isInitialized) postFinished(appContext, info)
        }

        override fun onResumed(info: TransferInfo) {
            Diagnostics.recorder.onFileResumed()
        }
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        PROGRESS_NOTIFICATION_ID,
        MainActivity.intent(context, Destination.Transfers),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateProgressNotification(transfers: List<TransferInfo>) {
        val context = appContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = transfers.filter { !it.finished }
        if (active.isEmpty()) {
            manager.cancel(PROGRESS_NOTIFICATION_ID)
            return
        }
        val current = active.first()
        val verb = if (current.direction == TransferDirection.OUTGOING) "Sending" else "Receiving"
        val title = if (active.size > 1) "$verb ${current.name} (+${active.size - 1})" else "$verb ${current.name}"
        val percent = if (current.size > 0) (current.transferred * 100 / current.size).toInt() else 0
        val text = when (current.status) {
            TransferStatus.WAITING -> "Waiting for the Mac"
            TransferStatus.COMPRESSING -> "Compressing…"
            else -> "$percent%"
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, current.status != TransferStatus.ACTIVE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()
        manager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun postFinished(context: Context, info: TransferInfo) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val builder = Notification.Builder(context, CHANNEL_ID).setAutoCancel(true)
        when {
            info.status == TransferStatus.COMPLETED && info.direction == TransferDirection.INCOMING -> {
                val saved = info.saved ?: return
                val uri = Uri.parse(saved.location)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Received ${saved.displayName}")
                    .setContentText("Saved to Downloads/Droidtosh")
                    .setContentIntent(
                        PendingIntent.getActivity(context, info.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
                    )
            }
            info.status == TransferStatus.COMPLETED ->
                builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Sent ${info.name} to your Mac")
                    .setContentIntent(openAppIntent(context))
            info.status == TransferStatus.CANCELLED && info.error == "user" -> return
            else -> {
                val verb = if (info.direction == TransferDirection.OUTGOING) "send" else "receive"
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Couldn't $verb ${info.name}")
                    .setContentText(failureText(info.error))
                    .setContentIntent(openAppIntent(context))
            }
        }
        manager.notify(info.id.hashCode(), builder.build())
    }

    fun failureText(error: String?): String = when (error) {
        "too_large" -> "The file is too large (2 GB limit)"
        FolderSender.ZIP_TOO_LARGE -> "The zipped folder is over 2 GB"
        FolderSender.ZIP_FAILED -> "The folder couldn't be zipped"
        "disk_full" -> "Not enough storage space"
        "disabled" -> "Receiving files is turned off on the other device"
        "busy" -> "Too many transfers at once"
        "checksum_mismatch" -> "The file was corrupted in transit"
        "write_failed" -> "The file couldn't be saved"
        "read_failed" -> "The file couldn't be read"
        "timeout" -> "The transfer timed out"
        FileTransferEngine.NOT_SUPPORTED -> "Not supported by the Mac"
        "user" -> "Cancelled"
        null, "" -> "Transfer failed"
        else -> error
    }
}

/** A content URI held open from the moment it was shared, so the grant can't lapse mid-transfer. */
private class UriSource(
    override val name: String,
    override val mimeType: String,
    override val size: Long,
    override val modifiedAtMs: Long,
    private val descriptor: ParcelFileDescriptor,
) : FileSource {
    override fun open(offset: Long): InputStream {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup())
        // A dup shares the file offset with the original, so every open positions explicitly.
        try {
            stream.channel.position(offset)
        } catch (error: Exception) {
            stream.close()
            throw IOException("source isn't seekable", error)
        }
        return stream
    }

    override fun currentSize(): Long = runCatching { descriptor.statSize }.getOrDefault(size).let { if (it < 0) size else it }

    override fun close() {
        runCatching { descriptor.close() }
    }

    companion object {
        fun open(resolver: ContentResolver, uri: Uri): UriSource {
            var name: String? = null
            var size = -1L
            var modified = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) modified = cursor.getLong(it) }
                }
            }
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("no descriptor for $uri")
            if (size < 0) size = descriptor.statSize
            if (size < 0) {
                descriptor.close()
                throw IOException("unknown size for $uri")
            }
            return UriSource(
                name = name ?: uri.lastPathSegment ?: "file",
                mimeType = resolver.getType(uri).orEmpty(),
                size = size,
                modifiedAtMs = modified,
                descriptor = descriptor,
            )
        }
    }
}

/** Partials in no-backup app storage; finished files copied into MediaStore Downloads/Droidtosh. */
private class MediaStoreDownloads(private val context: Context) : IncomingFileStore {
    private val directory = File(context.noBackupFilesDir, "incoming")

    override fun partialFile(transferId: String) = File(directory, "$transferId.part")

    override fun usableSpace(): Long {
        directory.mkdirs()
        return directory.usableSpace
    }

    override fun commit(partial: File, name: String, mimeType: String): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val unique = FileNames.unique(name, existingNames(resolver, collection)::contains)
        // MediaStore rewrites extensions that don't match the MIME type, so derive it from the name.
        val extension = FileNames.split(unique).second.removePrefix(".").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, unique)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> partial.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("no output stream for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        val saved = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: unique
        return SavedFile(saved, uri.toString())
    }

    private fun existingNames(resolver: ContentResolver, collection: Uri): Set<String> =
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(RELATIVE_PATH),
            null,
        )?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } ?: emptySet()

    override fun deleteOrphans(keep: Set<File>) {
        directory.listFiles()?.filter { it !in keep }?.forEach { it.delete() }
    }

    companion object {
        private const val RELATIVE_PATH = "Download/Droidtosh/"
    }
}

/** A folder of a document tree (ACTION_OPEN_DOCUMENT_TREE), read through DocumentsContract. */
private class DocumentTreeNode(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    override val name: String,
    override val isDirectory: Boolean,
    override val modifiedAtMs: Long,
) : FolderNode {
    override fun children(): List<FolderNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        return resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(fromCursor(resolver, treeUri, cursor)) }
        }.orEmpty().sortedBy { it.name }
    }

    override fun open(): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
            ?: throw IOException("can't open $name")

    companion object {
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun root(resolver: ContentResolver, treeUri: Uri): DocumentTreeNode {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            return resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) fromCursor(resolver, treeUri, cursor) else null
            } ?: throw IOException("no folder at $treeUri")
        }

        private fun fromCursor(resolver: ContentResolver, treeUri: Uri, cursor: android.database.Cursor) = DocumentTreeNode(
            resolver = resolver,
            treeUri = treeUri,
            documentId = cursor.getString(0),
            name = cursor.getString(1).orEmpty(),
            isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
            modifiedAtMs = if (cursor.isNull(3)) 0L else cursor.getLong(3),
        )
    }
}
