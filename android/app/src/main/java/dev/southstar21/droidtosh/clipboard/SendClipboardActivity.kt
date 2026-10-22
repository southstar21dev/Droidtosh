package dev.southstar21.droidtosh.clipboard

import android.app.Activity
import android.widget.Toast

class SendClipboardActivity : Activity() {
    private var dispatched = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || dispatched) return
        dispatched = true
        val manager = ClipboardSyncManager(applicationContext, isEnabled = { true })
        if (!manager.sendCurrentClipboard()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
