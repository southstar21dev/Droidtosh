package dev.southstar21.droidtosh.device

import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a [FindPhoneController] from [TransportHub]: feeds it the DEVICE inbox and wakes it when
 * the ring is due to time out. Shared by the connection service and the end-to-end phone peer.
 */
class FindPhoneRunner(private val controller: FindPhoneController) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    /** Starts driving the controller in [scope]; a no-op while already running. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (jobs.any { it.isActive }) return
        jobs.clear()
        jobs += scope.launch {
            TransportHub.incoming(InboundFeature.DEVICE).collect { envelope ->
                controller.onEnvelope(envelope)
                wake.trySend(Unit)
            }
        }
        jobs += scope.launch {
            while (isActive) {
                controller.tick()
                val wait = controller.nextWakeInMs()
                if (wait == null) wake.receive() else if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Call after acting on the controller directly (e.g. the Stop button), so the timer re-arms. */
    fun poke() {
        wake.trySend(Unit)
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        controller.shutdown()
    }
}
