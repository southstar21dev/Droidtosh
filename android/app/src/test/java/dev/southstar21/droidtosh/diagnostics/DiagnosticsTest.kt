package dev.southstar21.droidtosh.diagnostics

import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.RelayTlsException
import dev.southstar21.droidtosh.transport.ReliableStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class DiagnosticsTest {
    private var now = 1_000_000L
    private fun recorder(capacity: Int = 20) = DiagnosticsRecorder(clock = { now }, eventCapacity = capacity)

    private fun DiagnosticsRecorder.snap() =
        snapshot(pushTokenPresent = false, dropped = emptyMap(), reliable = null)

    @Test
    fun eventLogKeepsMostRecentInOrder() {
        val log = EventLog(capacity = 3)
        (1..5).forEach { log.add(DiagnosticEvent(it.toLong(), EventKind.INFO, "e$it")) }
        assertEquals(listOf("e3", "e4", "e5"), log.snapshot().map { it.message })
    }

    @Test
    fun rttStatsTrackLastAvgMinMax() {
        val rtt = RttStats()
        assertNull(rtt.summary())
        listOf(40L, 10L, 70L).forEach(rtt::record)
        rtt.record(-5) // clock skew: ignored
        assertEquals(RttSummary(lastMs = 70, avgMs = 40, minMs = 10, maxMs = 70, samples = 3), rtt.summary())
        rtt.reset()
        assertNull(rtt.summary())
    }

    @Test
    fun reconnectsCountedUnderFirstReasonOnNextAttempt() {
        val r = recorder()
        r.onConnectAttempt() // first attempt: not a reconnect
        assertEquals(0, r.snap().reconnects)

        r.onSessionEnd(DisconnectReason.NETWORK_CHANGE)
        r.onSessionEnd(DisconnectReason.NETWORK_ERROR, "socket closed") // fallout of the same drop
        r.onConnectAttempt()
        r.onSessionEnd(DisconnectReason.RELAY_WAIT)
        r.onConnectAttempt()
        r.onConnectAttempt() // no session ended in between: not counted

        val snap = r.snap()
        assertEquals(2, snap.reconnects)
        assertEquals(
            mapOf(DisconnectReason.NETWORK_CHANGE to 1L, DisconnectReason.RELAY_WAIT to 1L),
            snap.reconnectReasons,
        )
    }

    @Test
    fun sessionStartResetsSessionTrafficButNotTotal() {
        val r = recorder()
        r.onSessionStart(2, listOf("reliable", "calls"))
        r.onFrameIn(100)
        r.onFrameOut(40)
        r.onRtt(25)
        now += 5_000
        var snap = r.snap()
        assertEquals(TrafficCounts(1, 1, 100, 40), snap.sessionTraffic)
        assertEquals(listOf("calls", "reliable"), snap.capabilities)
        assertEquals(2, snap.negotiatedVersion)
        assertEquals(now - 5_000, snap.sessionStartedAtMs)

        r.onSessionEnd(DisconnectReason.TIMEOUT)
        r.onSessionStart(2, listOf("calls"))
        r.onFrameIn(10)
        snap = r.snap()
        assertEquals(TrafficCounts(1, 0, 10, 0), snap.sessionTraffic)
        assertEquals(TrafficCounts(2, 1, 110, 40), snap.totalTraffic)
        assertNull("RTT is per session", snap.rtt)
    }

    @Test
    fun sessionEndClearsNegotiatedState() {
        val r = recorder()
        r.onSessionStart(2, listOf("calls"))
        r.onSessionEnd(DisconnectReason.MAC_ASLEEP)
        val snap = r.snap()
        assertNull(snap.negotiatedVersion)
        assertTrue(snap.capabilities.isEmpty())
        assertNull(snap.sessionStartedAtMs)
        assertEquals(EventKind.INFO, snap.events.last().kind)
    }

    @Test
    fun stateOnlyRecordsChanges() {
        val r = recorder()
        r.onState("Connecting")
        now += 1_000
        r.onState("Connecting")
        val snap = r.snap()
        assertEquals("Connecting", snap.state)
        assertEquals(now - 1_000, snap.stateSinceMs)
        assertEquals(1, snap.events.count { it.kind == EventKind.STATE })
    }

    @Test
    fun countersAreThreadSafe() {
        val r = recorder()
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                repeat(10_000) {
                    r.onFrameOut(1)
                    r.onInbound(InboundFeature.CLIPBOARD)
                    r.onClipboardSent()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        val snap = r.snap()
        assertEquals(80_000, snap.totalTraffic.envelopesOut)
        assertEquals(80_000, snap.inboundByFeature.getValue(InboundFeature.CLIPBOARD))
        assertEquals(80_000, snap.clipboard.sent)
    }

    @Test
    fun snapshotDropsAreAccumulatedAndLogged() {
        val r = recorder()
        r.onSnapshot(records = 10, bytes = 500_000, iconsDropped = 0, recordsDropped = 0)
        r.onSnapshot(records = 30, bytes = 899_000, iconsDropped = 3, recordsDropped = 2)
        val n = r.snap().notifications
        assertEquals(30, n.lastSnapshotRecords)
        assertEquals(3, n.snapshotIconsDropped)
        assertEquals(2, n.snapshotRecordsDropped)
        assertEquals(1, r.snap().events.size)
    }

    @Test
    fun endpointKeepsOnlyShortFingerprints() {
        val r = recorder()
        r.onEndpoint("relay.example", 7443, "a".repeat(64), "b".repeat(64))
        assertEquals(Endpoint("relay.example", 7443, "aaaaaaaa", "bbbbbbbb"), r.snap().endpoint)
    }

    @Test
    fun classifiesErrors() {
        assertEquals(DisconnectReason.RELAY_TLS, DisconnectReason.classify(RelayTlsException("x", IOException())))
        assertEquals(DisconnectReason.MAC_TLS, DisconnectReason.classify(SSLHandshakeException("pin")))
        assertEquals(DisconnectReason.TIMEOUT, DisconnectReason.classify(SocketTimeoutException()))
        assertEquals(DisconnectReason.CONNECTION_CLOSED, DisconnectReason.classify(EOFException()))
        assertEquals(DisconnectReason.RELAY_ERROR, DisconnectReason.classify(IOException("relay error: busy")))
        assertEquals(DisconnectReason.NETWORK_ERROR, DisconnectReason.classify(IOException("reset")))
        assertEquals(DisconnectReason.PROTOCOL, DisconnectReason.classify(IllegalStateException("bad psk")))
        assertEquals(DisconnectReason.OTHER, DisconnectReason.classify(RuntimeException()))
    }
}

class DiagnosticsReportTest {
    private val room = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde" // 43 chars
    private val psk = "cHNrLXNlY3JldC1rZXktbWF0ZXJpYWwtMzItYnl0ZXM"
    private val token = "fcm:APA91bH-token-value"
    private val relayFp = "0123456789abcdef".repeat(4)

    @Test
    fun redactorRemovesSecretsAndShortensFingerprints() {
        val redactor = Redactor(listOf(room, psk, token))
        val text = "join DROIDSYNC/2 $room phone $token psk=$psk fp=$relayFp"
        val out = redactor.redact(text)
        assertFalse(out.contains(room))
        assertFalse(out.contains(psk))
        assertFalse(out.contains(token))
        assertFalse(out.contains(relayFp))
        assertTrue(out.contains("fp=01234567…"))
    }

    @Test
    fun redactorCatchesUnknownRoomShapedTokens() {
        val unknownRoom = "Zz".repeat(21) + "q" // 43 base64url chars the redactor wasn't told about
        assertEquals("room=[redacted] ok", Redactor(emptyList()).redact("room=$unknownRoom ok"))
    }

    @Test
    fun redactorLeavesOrdinaryTextAlone() {
        val text = "Disconnected (Mac asleep) · relay.example.com:7443 · v2"
        assertEquals(text, Redactor(listOf("short")).redact(text))
    }

    @Test
    fun reportHasSectionsAndNoSecrets() {
        val recorder = DiagnosticsRecorder(clock = { 1_700_000_000_000 })
        recorder.onEndpoint("relay.example", 7443, relayFp, relayFp)
        recorder.onSessionStart(2, listOf("reliable", "notifications"))
        recorder.onSessionEnd(DisconnectReason.NETWORK_ERROR, "IOException: write to $room failed")
        recorder.onConnectAttempt()
        recorder.onRtt(42)
        val snapshot = recorder.snapshot(
            pushTokenPresent = true,
            dropped = mapOf(InboundFeature.CLIPBOARD to 2L),
            reliable = ReliableStream.Stats(1, 0, 0, 0, 3, 4),
        )
        val report = DiagnosticsReport.format(
            snapshot,
            ReportHeader("1.2.3", "15", "Google Pixel 8"),
            Redactor(listOf(room, psk, token)),
        )
        listOf(
            "[Connection]", "[Round trip]", "[Traffic]", "[Reliable delivery]",
            "[Clipboard]", "[Notifications]", "[Recent events]",
        ).forEach { assertTrue("missing $it", report.contains(it)) }
        assertTrue(report.contains("Relay: relay.example:7443"))
        assertTrue(report.contains("Relay fingerprint: 01234567…"))
        assertTrue(report.contains("Reconnects: 1 (network error 1)"))
        assertTrue(report.contains("Retransmits: 4"))
        assertTrue(report.contains("Push token: present"))
        assertFalse(report.contains(room))
        assertFalse(report.contains(relayFp))
    }

    @Test
    fun formatsDurationsAndBytes() {
        assertEquals("59s", DiagnosticsReport.duration(59_999))
        assertEquals("2m 5s", DiagnosticsReport.duration(125_000))
        assertEquals("3h 4m", DiagnosticsReport.duration((3 * 3600 + 4 * 60) * 1000L))
        assertEquals("512 B", DiagnosticsReport.bytes(512))
        assertEquals("1.5 KB", DiagnosticsReport.bytes(1536))
        assertEquals("2.0 MB", DiagnosticsReport.bytes(2L * 1024 * 1024))
    }

    @Test
    fun reportDescribesBatteryOptimization() {
        assertEquals("off (unrestricted)", DiagnosticsReport.batteryOptimizationText(true))
        assertEquals("on (may pause the connection)", DiagnosticsReport.batteryOptimizationText(false))
        assertEquals("—", DiagnosticsReport.batteryOptimizationText(null))
        val snapshot = DiagnosticsRecorder(clock = { 1_000L })
            .snapshot(pushTokenPresent = false, dropped = emptyMap(), reliable = null, batteryOptimizationIgnored = true)
        val report = DiagnosticsReport.format(snapshot, ReportHeader("1", "15", "Pixel"), Redactor(emptyList()))
        assertTrue(report.contains("[Background]"))
        assertTrue(report.contains("Battery optimization: off (unrestricted)"))
    }

    @Test
    fun reportDescribesOverlayPermission() {
        assertEquals("—", DiagnosticsReport.overlayText(null))
        val snapshot = DiagnosticsRecorder(clock = { 1_000L })
            .snapshot(pushTokenPresent = false, dropped = emptyMap(), reliable = null, overlayAllowed = false)
        val report = DiagnosticsReport.format(snapshot, ReportHeader("1", "15", "Pixel"), Redactor(emptyList()))
        assertTrue(report.contains("Display over other apps: not allowed (app calls can't be answered while unlocked)"))
        val allowed = DiagnosticsRecorder(clock = { 1_000L })
            .snapshot(pushTokenPresent = false, dropped = emptyMap(), reliable = null, overlayAllowed = true)
        assertTrue(
            DiagnosticsReport.format(allowed, ReportHeader("1", "15", "Pixel"), Redactor(emptyList()))
                .contains("Display over other apps: allowed")
        )
    }
}
