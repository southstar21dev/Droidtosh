package dev.southstar21.droidtosh.clipboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.files.ShareUriFilter

class SendToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Another app can start this exported activity, so the clipboard-sync switch applies here too.
        if (!ClipboardPreference.isEnabled(applicationContext)) {
            Toast.makeText(applicationContext, R.string.share_clipboard_off, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val manager = ClipboardSyncManager(applicationContext, isEnabled = { true })
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    if (ShareUriFilter.isAllowed(uri, packageName)) manager.sendSharedImage(uri)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let(manager::sendSharedText)
                }
            }
        }
        finish()
    }
}
