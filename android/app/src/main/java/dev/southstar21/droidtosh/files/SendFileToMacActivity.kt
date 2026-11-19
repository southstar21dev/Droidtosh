package dev.southstar21.droidtosh.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.southstar21.droidtosh.ui.Destination
import dev.southstar21.droidtosh.ui.MainActivity
import dev.southstar21.droidtosh.ui.ShareResult
import dev.southstar21.droidtosh.ui.ShareSheet
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme
import kotlinx.coroutines.delay

/**
 * Share target "Send file to Mac": shows the shared file names and waits for the user to tap Send
 * (any foreground app can fire this share, so nothing is queued unprompted), then queues the files
 * and shows a short confirmation that closes itself.
 */
class SendFileToMacActivity : ComponentActivity() {
    private var sent: ShareResult? = null
    private var pending: Pending? = null

    /** What the share offers: the allowed URIs, their names, and how many were shared in all. */
    private class Pending(val uris: List<Uri>, val names: List<String>, val total: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Queue once: a recreated sheet (rotation) shows the earlier result, or asks again if unsent.
        sent = savedInstanceState?.restoreResult()
        if (sent == null) pending = readShared()
        setContent {
            DroidtoshTheme {
                var result by remember { mutableStateOf(sent) }
                val offer = pending
                if (result != null) {
                    LaunchedEffect(Unit) {
                        delay(AUTO_DISMISS_MS)
                        finish()
                    }
                }
                val shown = result ?: ShareResult(
                    queued = offer?.uris?.size ?: 0,
                    total = offer?.total ?: 0,
                    names = offer?.names.orEmpty(),
                )
                ShareSheet(
                    result = shown,
                    onDone = ::finish,
                    onOpenTransfers = {
                        startActivity(MainActivity.intent(this, Destination.Transfers))
                        finish()
                    },
                    onSend = if (result == null && offer != null && offer.uris.isNotEmpty()) {
                        // Two taps can land before the recomposition that hides Send; queue once.
                        { if (result == null && sent == null) result = send(offer) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sent?.let { result ->
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

    private fun readShared(): Pending {
        val requested = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Exported target: drop file:// and this app's own provider URIs before touching them.
        val uris = requested.filter { ShareUriFilter.isAllowed(it, packageName) }
        // Read names now; the files are opened on Send, while the share grant still lives with this activity.
        return Pending(uris, uris.mapNotNull(::displayName), requested.size)
    }

    private fun send(offer: Pending): ShareResult {
        val queued = if (offer.uris.isEmpty()) 0 else FileTransfers.send(applicationContext, offer.uris)
        val result = ShareResult(queued = queued, total = offer.total, names = if (queued == 0) emptyList() else offer.names)
        sent = result
        return result
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
