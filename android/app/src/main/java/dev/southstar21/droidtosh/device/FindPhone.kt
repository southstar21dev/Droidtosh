package dev.southstar21.droidtosh.device

import android.content.Context
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindEndReason
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Find my phone (protocol/PROTOCOL-v2.md §9): a fresh [FindPhoneController] per connection service
 * start, so request ids are deduplicated per service start, with the [AndroidAlarm] as its ring.
 */
object FindPhone {
    private const val PREFERENCES = "droidsync_find"
    private const val ENABLED_KEY = "enabled"

    @Volatile
    private var controller: FindPhoneController? = null
    private var runner: FindPhoneRunner? = null

    private val idle = MutableStateFlow(false)

    /** True while the phone rings. */
    val ringing: StateFlow<Boolean> get() = controller?.ringing ?: idle.asStateFlow()

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (runner != null) return
        val appContext = context.applicationContext
        lateinit var created: FindPhoneController
        val alarm = AndroidAlarm(appContext) {
            created.onAlarmFailed()
            runner?.poke()
        }
        created = FindPhoneController(send = ::send, alarm = alarm, enabled = { enabled(appContext) })
        controller = created
        runner = FindPhoneRunner(created).also { it.start(scope) }
    }

    @Synchronized
    fun stop() {
        runner?.stop()
        runner = null
        controller = null
    }

    /** The Stop button, a dismissed notification, or a key on the ringing screen. */
    fun stopOnPhone() {
        controller?.stopOnPhone()
        runner?.poke()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, enabled).apply()
        if (!enabled) stopOnPhone()
    }

    private fun send(envelope: Envelope): Boolean {
        val state = envelope.findPhoneState
        val diagnostics = Diagnostics.recorder
        when {
            state.ringing -> diagnostics.onFindStarted()
            state.ended == FindEndReason.FIND_END_FAILED -> diagnostics.onFindFailed(state.error)
            else -> diagnostics.onFindStopped(endLabel(state.ended))
        }
        return TransportHub.send(envelope)
    }

    private fun endLabel(reason: FindEndReason): String = when (reason) {
        FindEndReason.FIND_END_STOPPED_ON_PHONE -> "on phone"
        FindEndReason.FIND_END_STOPPED_FROM_MAC -> "from Mac"
        FindEndReason.FIND_END_TIMEOUT -> "timeout"
        else -> reason.name
    }
}
