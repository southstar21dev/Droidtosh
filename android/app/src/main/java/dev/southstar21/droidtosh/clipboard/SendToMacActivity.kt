package dev.southstar21.droidtosh.clipboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class SendToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = ClipboardSyncManager(applicationContext, isEnabled = { true })
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    manager.sendSharedImage(uri)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let(manager::sendSharedText)
                }
            }
        }
        finish()
    }
}
