package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.data.CredentialsUnavailableException
import dev.southstar21.droidtosh.data.PairingCredentials
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.diagnostics.DisconnectReason
import dev.southstar21.droidtosh.protocol.v1.Ack
import dev.southstar21.droidtosh.protocol.v1.Hello
import dev.southstar21.droidtosh.protocol.v1.Ping
import dev.southstar21.droidtosh.protocol.v1.Pong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * The phone's relay connection loop (connect, handshake, Resume, read loop, backoff and the
 * Mac-asleep wait), free of Android framework types so the end-to-end harness runs the same code
 * on a plain JVM. [ConnectionService] supplies credentials, the Hello and the UI/notification side
 * through [Host].
 */
class PhoneConnectionLoop(
    private val host: Host,
    private val scope: CoroutineScope,
    private val reliableStream: ReliableStream,
    private val waitPolicy: WaitModePolicy,
    /** Wake-ups for the Mac-asleep / relay-WAIT wait (FCM `mac_online`, network, "reconnect now"). */
    private val waitSignal: Channel<Unit>,
    private val connector: TlsConnector = TlsConnector(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val timing: Timing = Timing(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Capabilities advertised in the handshake; the e2e harness narrows them to test gating. */
    private val capabilities: List<String> = Capabilities.SUPPORTED,
) {
    data class Timing(
        val pingIntervalMs: Long = 45_000L,
        val ackPollMs: Long = 250L,
        val resumeTimeoutMs: Long = 10_000L,
    )

    interface Host {
        /** The stored pairing, or null when unpaired; may throw [CredentialsUnavailableException]. */
        fun loadCredentials(): PairingCredentials?
        fun fcmToken(): String?
        fun deviceName(): String
        fun hello(deviceName: String): Hello
        /** Publishes [state]; [statusText] (when non-null) is the connection notification's text. */
        fun onState(state: ConnectionState, statusText: String?)
        /** Called once when there's no pairing; the loop then returns. */
        fun onNotPaired()
        /** True once the owner is shutting down; the loop returns instead of reconnecting. */
        val isDestroyed: Boolean

        /**
         * Where to find the Mac on the local network, best first (protocol/PROTOCOL-v2.md §1.4). The app
         * puts Bonjour results ahead of this default, the pairing's `lhost:lport`.
         */
        suspend fun localCandidates(credentials: PairingCredentials): List<LocalEndpoint> =
            listOfNotNull(
                credentials.localHost?.let { LocalEndpoint(it, credentials.localPort ?: LocalEndpoint.DEFAULT_PORT) },
            )
    }

    private val networkSignal = Channel<Unit>(Channel.CONFLATED)

    /** The current session's client, so the owner can close a socket blocked in a read. */
    @Volatile
    var activeClient: ProtocolClient? = null
        private set

    /** Skips the current backoff (network came back). */
    fun signalNetwork() {
        networkSignal.trySend(Unit)
    }

    suspend fun run() {
        var attempt = 0
        // How soon a WAIT is retried before falling back to the long Mac-asleep wait.
        val grace = RejoinGrace(clock).apply { onStart() }
        while (currentCoroutineContext().isActive) {
            val credentials = try {
                host.loadCredentials()
            } catch (error: CredentialsUnavailableException) {
                TransportLog.w(TAG, "credentials unavailable; retrying", error)
                host.onState(ConnectionState.Disconnected("Credentials unavailable"), "Disconnected - reconnecting")
                delay(reconnectPolicy.delayMs(attempt))
                attempt += 1
                continue
            }
            if (credentials == null) {
                host.onState(ConnectionState.NotPaired, null)
                host.onNotPaired()
                return
            }
            val mode = credentials.mode
            diagnostics.onEndpoint(
                credentials.host,
                credentials.port,
                credentials.relayFingerprint,
                credentials.fingerprint,
                mode.wire,
                credentials.localHost?.let { "$it:${credentials.localPort ?: LocalEndpoint.DEFAULT_PORT}" },
            )
            diagnostics.onConnectAttempt()
            host.onState(ConnectionState.Connecting, "Connecting")
            var client: ProtocolClient? = null
            var waitingForMac = false
            /** Set when a WAIT should be retried after this delay rather than the normal backoff. */
            var retryWaitMs: Long? = null
            var authenticated = false
            try {
                // §1.5: the relay first (unless local-only), then the local network on WAIT or failure.
                val relayResult = if (mode.usesRelay && credentials.hasRelay) dialRelay(credentials, attempt) else null
                var socket = (relayResult as? RelayConnectResult.Connected)?.socket
                var path = SessionPath.RELAY
                var localError: Exception? = null
                if (socket == null && mode.usesLocal) {
                    try {
                        socket = dialLocal(credentials)
                        path = SessionPath.LOCAL
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        localError = error
                    }
                }
                when {
                    socket != null -> {
                        client = ProtocolClient(SocketDuplexStream(socket), credentials.psk, capabilities = capabilities)
                        activeClient = client
                        if (host.isDestroyed) return
                        TransportLog.d(TAG, "${path.label} connected; handshaking")
                        client.performHandshake()
                        authenticated = true
                        attempt = 0
                        waitingForMac = runSession(client, path, mode)
                    }
                    relayResult == RelayConnectResult.Waiting -> {
                        retryWaitMs = grace.retryDelayMs(reconnectPolicy.delayMs(attempt))
                        if (retryWaitMs != null) {
                            TransportLog.d(TAG, "relay WAIT soon after a start or session; retrying in ${retryWaitMs}ms")
                            diagnostics.onSessionEnd(DisconnectReason.RELAY_WAIT, "Mac not re-parked yet")
                            host.onState(ConnectionState.Disconnected("Reconnecting to Mac"), "Disconnected - reconnecting")
                        } else {
                            TransportLog.d(TAG, "relay WAIT (no mac parked)")
                            diagnostics.onSessionEnd(DisconnectReason.RELAY_WAIT)
                            waitingForMac = true
                        }
                    }
                    relayResult is RelayConnectResult.Failed -> throw relayResult.cause
                    localError != null -> throw localError
                    else -> throw IOException("This pairing has no relay; choose Local network or re-pair")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (host.isDestroyed) return
                TransportLog.d(TAG, "session error: ${error.javaClass.simpleName}: ${error.message}", error)
                diagnostics.onSessionEnd(
                    DisconnectReason.classify(error),
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                host.onState(
                    ConnectionState.Disconnected(error.message ?: error.javaClass.simpleName),
                    "Disconnected - reconnecting",
                )
            } finally {
                client?.let { TransportHub.detach(it) }
                if (activeClient === client) activeClient = null
                runCatching { client?.close() }
                if (authenticated) grace.onSessionEnded(macAsleep = waitingForMac)
            }
            if (host.isDestroyed) return
            val retryMs = retryWaitMs
            if (waitingForMac) {
                waitForMac()
                attempt = 0
            } else if (retryMs != null) {
                // A push or "reconnect now" ends the wait early, as does the network coming back.
                withTimeoutOrNull(retryMs) {
                    select {
                        networkSignal.onReceive {}
                        waitSignal.onReceive {}
                    }
                }
                attempt += 1
            } else {
                withTimeoutOrNull(reconnectPolicy.delayMs(attempt)) { networkSignal.receive() }
                attempt += 1
            }
        }
    }

    /** Runs an authenticated session until it ends; returns true when the Mac went to sleep. */
    private suspend fun runSession(client: ProtocolClient, path: SessionPath, mode: ConnectionMode): Boolean {
        val capabilities = client.capabilities
        diagnostics.onSessionStart(client.negotiatedVersion, capabilities, path.label)
        val via = if (path == SessionPath.LOCAL) " via local network" else ""
        val stream = reliableStream.takeIf { Capabilities.RELIABLE in capabilities }
        val peerResumed = AtomicBoolean(false)
        val deviceName = host.deviceName()
        val sendHello = { client.send(newEnvelope { setHello(host.hello(deviceName)) }) }
        if (stream != null) {
            // Feature traffic waits until both Resumes are exchanged (§5).
            client.reliable = stream
            stream.beginSession()
            TransportHub.attach(client, stream, capabilities, ready = false)
            client.send(newEnvelope { setResume(stream.resume()) })
            // Without the Mac's Resume nothing would ever be sent; reconnect instead.
            scope.launch {
                delay(timing.resumeTimeoutMs)
                if (!peerResumed.get() && activeClient === client) {
                    diagnostics.onSessionEnd(DisconnectReason.RESUME_TIMEOUT)
                    closeInBackground(client)
                }
            }
        } else {
            sendHello()
            TransportHub.attach(client, null, capabilities, ready = true)
        }
        host.onState(ConnectionState.Connected(deviceName, path = path), "Connected$via")
        val pinger = scope.launch {
            var sincePing = 0L
            while (isActive) {
                delay(timing.ackPollMs)
                sincePing += timing.ackPollMs
                runCatching {
                    if (sincePing >= timing.pingIntervalMs) {
                        sincePing = 0
                        client.send(newEnvelope {
                            setPing(Ping.newBuilder().setSentAtMs(System.currentTimeMillis()))
                        })
                    } else if (stream?.ackDue() == true) {
                        client.send(newEnvelope { setAckOnly(Ack.getDefaultInstance()) })
                    }
                }
            }
        }
        try {
            while (currentCoroutineContext().isActive) {
                val envelope = client.receive()
                if (stream?.onReceive(envelope) == ReliableStream.Decision.DUPLICATE) continue
                when {
                    envelope.hasResume() && stream != null -> {
                        val retransmit = stream.onPeerResume(envelope.resume)
                        if (retransmit.isNotEmpty()) TransportLog.d(TAG, "retransmitting ${retransmit.size}")
                        retransmit.forEach(client::send)
                        if (!peerResumed.getAndSet(true)) sendHello()
                        TransportHub.markReady(client)
                    }
                    envelope.hasAckOnly() -> Unit
                    envelope.hasPong() -> {
                        val sentAt = envelope.pong.sentAtMs
                        if (sentAt > 0) diagnostics.onRtt(System.currentTimeMillis() - sentAt)
                    }
                    envelope.hasPing() -> client.send(newEnvelope {
                        setPong(Pong.newBuilder().setSentAtMs(envelope.ping.sentAtMs))
                    })
                    envelope.hasServerInfo() -> {
                        val info = envelope.serverInfo
                        host.onState(
                            ConnectionState.Connected(
                                deviceName = deviceName,
                                macName = info.deviceName,
                                macModel = info.model,
                                path = path,
                            ),
                            "Connected to ${info.deviceName}$via",
                        )
                    }
                    envelope.hasHostStatus() && envelope.hostStatus.sleeping -> {
                        // Drop wake-ups that predate the Mac going to sleep.
                        while (waitSignal.tryReceive().isSuccess) {
                        }
                        diagnostics.onSessionEnd(DisconnectReason.MAC_ASLEEP)
                        host.onState(ConnectionState.Disconnected("Mac asleep"), "Waiting for Mac")
                        macAsleepMode = mode
                        return true
                    }
                    else -> TransportHub.dispatch(envelope)
                }
            }
        } finally {
            pinger.cancel()
        }
        return false
    }

    /** The mode of the session that ended with the Mac asleep; a local-only wait can't count on FCM. */
    private var macAsleepMode = ConnectionMode.RELAY

    private suspend fun waitForMac() {
        host.onState(ConnectionState.Disconnected("Waiting for Mac"), "Waiting for Mac")
        withTimeoutOrNull(waitPolicy.nextDelayMs(fcmUsable = macAsleepMode.usesRelay)) { waitSignal.receive() }
    }

    private fun dialRelay(credentials: PairingCredentials, attempt: Int): RelayConnectResult {
        val token = host.fcmToken()
        TransportLog.d(
            TAG,
            "attempt=$attempt room=${credentials.room.take(8)} token=${if (token.isNullOrEmpty()) "none" else "present"}",
        )
        val joinLine = RelayJoinLine.format(credentials.room, PHONE_ROLE, token)
        return connector.connect(
            checkNotNull(credentials.host),
            checkNotNull(credentials.port),
            joinLine,
            checkNotNull(credentials.relayFingerprint),
            credentials.fingerprint,
        )
    }

    /** Tries each local candidate in turn (§1.4); throws the last failure when none answers. */
    private suspend fun dialLocal(credentials: PairingCredentials): SSLSocket {
        val candidates = host.localCandidates(credentials).distinct()
        var lastError: Exception = IOException("Mac not found on the local network")
        for (candidate in candidates) {
            try {
                TransportLog.d(TAG, "trying local ${candidate.host}:${candidate.port}")
                return connector.connectDirect(candidate.host, candidate.port, credentials.fingerprint)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                TransportLog.d(TAG, "local ${candidate.host}:${candidate.port} failed: ${error.message}")
                lastError = error
            }
        }
        throw lastError
    }

    companion object {
        private const val TAG = "DroidsyncConn"
        private const val PHONE_ROLE = "phone"

        private val diagnostics get() = Diagnostics.recorder

        fun closeInBackground(client: ProtocolClient) {
            // Closing a TLS socket writes close_notify, which isn't allowed on the main thread.
            Thread { runCatching { client.close() } }.start()
        }
    }
}
