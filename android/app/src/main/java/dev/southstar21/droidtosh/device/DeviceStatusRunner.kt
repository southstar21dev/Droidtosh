package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a [DeviceStatusEngine] from [TransportHub]: tells it when a session's feature traffic starts
 * and stops, and sleeps until its next deadline or the next source change ([poke]). Shared by the
 * connection service and the end-to-end phone peer.
 */
class DeviceStatusRunner(private val engine: DeviceStatusEngine) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    /** Starts driving the engine in [scope]; a no-op while already running. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (jobs.any { it.isActive }) return
        jobs.clear()
        jobs += scope.launch {
            TransportHub.readySession.collect { session ->
                if (session == null) engine.onSessionEnded() else engine.onSessionStarted(session.capabilities)
                wake.trySend(Unit)
            }
        }
        jobs += scope.launch {
            while (isActive) {
                engine.tick()
                val wait = engine.nextWakeInMs()
                if (wait == null) wake.receive() else if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** The source changed: re-evaluates it now. */
    fun poke() {
        engine.onSourceChanged()
        wake.trySend(Unit)
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        engine.onSessionEnded()
    }
}
