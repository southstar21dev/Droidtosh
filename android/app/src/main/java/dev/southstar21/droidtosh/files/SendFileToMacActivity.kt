package dev.southstar21.droidtosh.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import dev.southstar21.droidtosh.ui.Destination
import dev.southstar21.droidtosh.ui.MainActivity
import dev.southstar21.droidtosh.ui.ShareResult
import dev.southstar21.droidtosh.ui.ShareSheet
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme
import kotlinx.coroutines.delay

/**
 * Share target "Send file to Mac": queues any shared files (one or many) for the Mac, then shows a
 * short confirmation sheet that closes itself.
 */
class SendFileToMacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Queue once: a recreated sheet (rotation) only shows the earlier result again.
        val result = savedInstanceState?.restoreResult() ?: queueShared()
        setContent {
            DroidtoshTheme {
                LaunchedEffect(Unit) {
                    delay(AUTO_DISMISS_MS)
                    finish()
                }
                ShareSheet(
                    result = result,
                    onDone = ::finish,
                    onOpenTransfers = {
                        startActivity(MainActivity.intent(this, Destination.Transfers))
                        finish()
                    },
                )
            }
        }
        lastResult = result
    }

    private var lastResult: ShareResult? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastResult?.let { result ->
            outState.putInt(KEY_QUEUED, result.queued)
            outState.putInt(KEY_TOTAL, result.total)
            outState.putStringArrayList(KEY_NAMES, ArrayList(result.names))
        }
    }

    private fun Bundle.restoreResult(): ShareResult? =
        if (containsKey(KEY_QUEUED)) {
            ShareResult(getInt(KEY_QUEUED), getInt(KEY_TOTAL), getStringArrayList(KEY_NAMES).orEmpty())
        } else {
            null
        }

    private fun queueShared(): ShareResult {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Read names and open the files now: the share grant ends with this activity.
        val names = uris.mapNotNull(::displayName)
        val queued = if (uris.isEmpty()) 0 else FileTransfers.send(applicationContext, uris)
        return ShareResult(queued = queued, total = uris.size, names = if (queued == 0) emptyList() else names)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private companion object {
        const val AUTO_DISMISS_MS = 4_000L
        const val KEY_QUEUED = "queued"
        const val KEY_TOTAL = "total"
        const val KEY_NAMES = "names"
    }
}
