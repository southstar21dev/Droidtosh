package dev.southstar21.droidtosh.files

import dev.southstar21.droidtosh.transport.Capabilities
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a [FileTransferEngine] from [TransportHub]: feeds it the FILES inbox, tells it when a
 * session with the `files` capability comes and goes, and pumps chunks out within the window.
 * Shared by the connection service and the end-to-end phone peer.
 */
class FileTransferRunner(private val engine: FileTransferEngine) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    /** Starts driving the engine in [scope]; a no-op while already running (the service restarts often). */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (jobs.any { it.isActive }) return
        jobs.clear()
        jobs += scope.launch {
            TransportHub.incoming(InboundFeature.FILES).collect { envelope ->
                engine.onEnvelope(envelope)
                wake.trySend(Unit)
            }
        }
        jobs += scope.launch {
            TransportHub.sessionCapabilities.map { capabilities ->
                when {
                    capabilities == null -> Session.NONE
                    Capabilities.FILES in capabilities -> Session.FILES
                    else -> Session.WITHOUT_FILES
                }
            }.distinctUntilChanged().collect { session ->
                when (session) {
                    Session.NONE -> engine.onDisconnected()
                    Session.FILES -> engine.onConnected()
                    Session.WITHOUT_FILES -> engine.onConnectedWithoutFiles()
                }
                wake.trySend(Unit)
            }
        }
        jobs += scope.launch {
            while (isActive) {
                while (engine.pump()) Unit
                // Woken by an inbound envelope (accept, progress, …), a connection change or a new file;
                // the timeout retries a chunk the outbound queue had no room for.
                withTimeoutOrNull(PUMP_RETRY_MS) { wake.receive() }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(TICK_MS)
                engine.tick()
            }
        }
    }

    /** Call after [FileTransferEngine.enqueue] so the new file starts without waiting for the retry delay. */
    fun poke() {
        wake.trySend(Unit)
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private enum class Session { NONE, FILES, WITHOUT_FILES }

    companion object {
        private const val PUMP_RETRY_MS = 250L
        private const val TICK_MS = 1_000L
    }
}
