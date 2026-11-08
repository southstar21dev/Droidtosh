package dev.southstar21.droidtosh.device

import android.content.Context
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.CoroutineScope

/**
 * Phone status for the Mac (protocol/PROTOCOL-v2.md §8): the framework callbacks are registered and
 * the engine runs only while the connection service is up.
 */
object PhoneStatus {
    private var source: AndroidDeviceStatusSource? = null
    private var runner: DeviceStatusRunner? = null

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (runner != null) return
        lateinit var created: DeviceStatusRunner
        val statusSource = AndroidDeviceStatusSource(context.applicationContext) { created.poke() }
        val engine = DeviceStatusEngine(source = statusSource, send = ::send)
        created = DeviceStatusRunner(engine)
        runner = created
        source = statusSource
        created.start(scope)
        statusSource.start()
    }

    /** The app's permissions may have changed (a permission result, or the app came to the foreground). */
    @Synchronized
    fun onPermissionsChanged() {
        source?.onPermissionsChanged()
    }

    @Synchronized
    fun stop() {
        source?.stop()
        source = null
        runner?.stop()
        runner = null
    }

    private fun send(envelope: Envelope): Boolean =
        TransportHub.send(envelope).also { if (it) Diagnostics.recorder.onDeviceStatusSent() }
}
