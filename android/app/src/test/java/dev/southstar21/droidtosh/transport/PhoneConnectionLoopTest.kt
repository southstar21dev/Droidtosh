package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.data.PairingCredentials
import dev.southstar21.droidtosh.protocol.v1.Hello
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneConnectionLoopTest {
    /** A relay that never has a Mac parked: every connect answers WAIT. */
    private class WaitingConnector(private val now: () -> Long) : TlsConnector() {
        val attemptsAt = mutableListOf<Long>()

        override fun connect(
            host: String,
            port: Int,
            joinLine: String,
            relayFingerprint: String,
            macFingerprint: String,
        ): RelayConnectResult {
            attemptsAt += now()
            return RelayConnectResult.Waiting
        }
    }

    /** Records relay and direct attempts in order; the relay answers [relayResult], direct dials fail. */
    private class RecordingConnector(private val relayResult: () -> RelayConnectResult) : TlsConnector() {
        val attempts = mutableListOf<String>()

        override fun connect(
            host: String,
            port: Int,
            joinLine: String,
            relayFingerprint: String,
            macFingerprint: String,
        ): RelayConnectResult {
            attempts += "relay"
            return relayResult()
        }

        override fun connectDirect(host: String, port: Int, macFingerprint: String): SSLSocket {
            attempts += "local $host:$port"
            throw IOException("no route to $host")
        }
    }

    private class FakeHost(
        private val mode: ConnectionMode = ConnectionMode.RELAY,
        private val relay: Boolean = true,
    ) : PhoneConnectionLoop.Host {
        val states = mutableListOf<ConnectionState>()

        override fun loadCredentials() = PairingCredentials(
            host = if (relay) "relay.invalid" else null,
            port = if (relay) 443 else null,
            room = "room",
            fingerprint = "aa",
            relayFingerprint = if (relay) "bb" else null,
            psk = ByteArray(32),
            mode = mode,
            localHost = "192.168.1.5",
            localPort = 7444,
        )
        override fun fcmToken(): String? = null
        override fun deviceName() = "Phone"
        override fun hello(deviceName: String): Hello = Hello.getDefaultInstance()
        override fun onState(state: ConnectionState, statusText: String?) {
            states += state
        }
        override fun onNotPaired() = Unit
        override val isDestroyed = false
    }

    @Before
    fun quietLog() {
        TransportLog.sink = TransportLog.Sink { _, _, _, _ -> }
    }

    @Test
    fun freshStartRetriesWaitWithBackoffBeforeTheLongWait() = runTest {
        val connector = WaitingConnector { testScheduler.currentTime }
        val loop = PhoneConnectionLoop(
            host = FakeHost(),
            scope = this,
            reliableStream = ReliableStream(clock = { testScheduler.currentTime }),
            waitPolicy = WaitModePolicy(fcmEnabled = false),
            waitSignal = Channel(Channel.CONFLATED),
            connector = connector,
            reconnectPolicy = ReconnectPolicy(jitterFraction = { 0.0 }),
            clock = { testScheduler.currentTime },
        )
        val job = launch { loop.run() }
        runCurrent()
        // Inside the 30 s start grace: 0, 1, 3, 7, 15 s, then 31 s is past it.
        advanceTimeBy(31_001L)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L), connector.attemptsAt)
        // Past the grace a WAIT means the Mac is asleep: the next try waits the full probe delay.
        advanceTimeBy(WaitModePolicy.NO_FCM_PROBE_MS - 1)
        assertEquals(6, connector.attemptsAt.size)
        advanceTimeBy(2L)
        assertEquals(31_000L + WaitModePolicy.NO_FCM_PROBE_MS, connector.attemptsAt.last())
        job.cancel()
    }

    @Test
    fun aPushDuringAWaitRetryWakesTheLoopAtOnce() = runTest {
        val connector = WaitingConnector { testScheduler.currentTime }
        val waitSignal = Channel<Unit>(Channel.CONFLATED)
        val loop = PhoneConnectionLoop(
            host = FakeHost(),
            scope = this,
            reliableStream = ReliableStream(clock = { testScheduler.currentTime }),
            waitPolicy = WaitModePolicy(fcmEnabled = true),
            waitSignal = waitSignal,
            connector = connector,
            reconnectPolicy = ReconnectPolicy(jitterFraction = { 0.0 }),
            clock = { testScheduler.currentTime },
        )
        val job = launch { loop.run() }
        runCurrent()
        advanceTimeBy(15_001L)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), connector.attemptsAt)
        // The next retry is due at 31 s; a mac_online push at 20 s skips the rest of the wait.
        advanceTimeBy(5_000L)
        waitSignal.trySend(Unit)
        runCurrent()
        assertEquals(20_001L, connector.attemptsAt.last())
        job.cancel()
    }

    private fun TestScope.loop(host: FakeHost, connector: TlsConnector, waitSignal: Channel<Unit> = Channel(Channel.CONFLATED)) =
        PhoneConnectionLoop(
            host = host,
            scope = this,
            reliableStream = ReliableStream(clock = { testScheduler.currentTime }),
            waitPolicy = WaitModePolicy(fcmEnabled = true),
            waitSignal = waitSignal,
            connector = connector,
            reconnectPolicy = ReconnectPolicy(jitterFraction = { 0.0 }),
            clock = { testScheduler.currentTime },
        )

    @Test
    fun relayModeNeverDialsTheLocalNetwork() = runTest {
        val connector = RecordingConnector { RelayConnectResult.Waiting }
        val job = launch { loop(FakeHost(ConnectionMode.RELAY), connector).run() }
        runCurrent()
        assertEquals(listOf("relay"), connector.attempts)
        job.cancel()
    }

    @Test
    fun bothModeTriesTheLocalNetworkAfterARelayWait() = runTest {
        val connector = RecordingConnector { RelayConnectResult.Waiting }
        val job = launch { loop(FakeHost(ConnectionMode.BOTH), connector).run() }
        runCurrent()
        assertEquals(listOf("relay", "local 192.168.1.5:7444"), connector.attempts)
        // The WAIT is then handled as usual: retried with the start-grace backoff, relay first again.
        advanceTimeBy(1_001L)
        assertEquals(listOf("relay", "local 192.168.1.5:7444", "relay", "local 192.168.1.5:7444"), connector.attempts)
        job.cancel()
    }

    @Test
    fun bothModeTriesTheLocalNetworkAfterARelayFailureAndReportsTheRelayError() = runTest {
        val connector = RecordingConnector { RelayConnectResult.Failed(IOException("relay down")) }
        val host = FakeHost(ConnectionMode.BOTH)
        val job = launch { loop(host, connector).run() }
        runCurrent()
        assertEquals(listOf("relay", "local 192.168.1.5:7444"), connector.attempts)
        assertEquals(ConnectionState.Disconnected("relay down"), host.states.last())
        job.cancel()
    }

    @Test
    fun localModeOnlyDialsTheMacAndBacksOff() = runTest {
        val connector = RecordingConnector { error("relay must not be dialled") }
        val host = FakeHost(ConnectionMode.LOCAL, relay = false)
        val job = launch { loop(host, connector).run() }
        runCurrent()
        assertEquals(listOf("local 192.168.1.5:7444"), connector.attempts)
        assertEquals(ConnectionState.Disconnected("no route to 192.168.1.5"), host.states.last())
        advanceTimeBy(1_001L)
        assertEquals(2, connector.attempts.size)
        job.cancel()
    }

    @Test
    fun bothModeWithALocalOnlyPairingSkipsTheRelay() = runTest {
        val connector = RecordingConnector { error("relay must not be dialled") }
        val job = launch { loop(FakeHost(ConnectionMode.BOTH, relay = false), connector).run() }
        runCurrent()
        assertEquals(listOf("local 192.168.1.5:7444"), connector.attempts)
        job.cancel()
    }
}
