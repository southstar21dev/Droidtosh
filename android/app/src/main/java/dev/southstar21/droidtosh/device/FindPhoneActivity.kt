package dev.southstar21.droidtosh.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.southstar21.droidtosh.ui.FindPhoneScreen
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The ringing screen, opened by the ring notification's full-screen intent (over the lock screen)
 * or by tapping it. The Stop button, a volume key, or the power key stops the ring; the screen
 * closes itself when the ring ends for any reason.
 */
class FindPhoneActivity : ComponentActivity() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stop()
        }
    }
    private var screenOffRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Otherwise the screen timing out mid-ring would read as a power-key press.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!FindPhone.ringing.value) {
            finish()
            return
        }
        lifecycleScope.launch {
            FindPhone.ringing.first { !it }
            finish()
        }
        enableEdgeToEdge()
        setContent {
            DroidtoshTheme {
                FindPhoneScreen(onStop = ::stop)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!screenOffRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenOff,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenOffRegistered = true
        }
    }

    override fun onStop() {
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOff) }
            screenOffRegistered = false
        }
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in STOP_KEYS) {
            stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun stop() {
        FindPhone.stopOnPhone()
        finish()
    }

    private companion object {
        val STOP_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
