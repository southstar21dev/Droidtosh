package dev.southstar21.droidtosh.clipboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.files.SendFileToMacActivity
import dev.southstar21.droidtosh.files.ShareUriFilter
import dev.southstar21.droidtosh.link.LinkPolicy
import dev.southstar21.droidtosh.link.Links
import dev.southstar21.droidtosh.sharing.ShareTarget

class SendToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                handleSend(intent)
                ShareTarget.reportUsed(this, intent)
            }
            // Direct Share matches by type only, so several images arrive here; the file flow owns them.
            Intent.ACTION_SEND_MULTIPLE -> forwardToFileFlow(intent)
        }
        finish()
    }

    /**
     * Hands a multi-item share to the file sheet (which filters the URIs and asks before sending).
     * Only the streams, their ClipData and a read grant are carried over: the caller's other flags
     * (task flags, persistable or prefix grants this app can't pass on) are not.
     */
    private fun forwardToFileFlow(incoming: Intent) {
        val streams = incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return
        val forward = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setClass(this, SendFileToMacActivity::class.java)
            .setType(incoming.type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        incoming.clipData?.let { forward.clipData = it }
        incoming.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { forward.putExtra(Intent.EXTRA_SHORTCUT_ID, it) }
        runCatching { startActivity(forward) }
    }

    private fun handleSend(intent: Intent) {
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val text = if (uri == null) intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() } else null
        // A single shared link goes to the Mac as a tap-to-open link. That never touches the Mac's
        // clipboard, so it works with clipboard sync switched off; the switch below only guards text and images.
        val link = text?.let(LinkPolicy::singleWebUrl)
        if (link != null && Links.send(link)) {
            Toast.makeText(applicationContext, R.string.share_link_sent, Toast.LENGTH_SHORT).show()
            return
        }
        // Another app can start this exported activity, so the clipboard-sync switch applies here too.
        if (!ClipboardPreference.isEnabled(applicationContext)) {
            Toast.makeText(
                applicationContext,
                if (link != null) R.string.share_link_failed else R.string.share_clipboard_off,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val manager = ClipboardSyncManager(applicationContext, isEnabled = { true })
        if (uri != null) {
            if (ShareUriFilter.isAllowed(uri, packageName)) manager.sendSharedImage(uri)
        } else if (text != null) {
            manager.sendSharedText(text)
        }
    }
}
