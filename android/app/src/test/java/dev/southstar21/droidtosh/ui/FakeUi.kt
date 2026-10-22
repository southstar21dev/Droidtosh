package dev.southstar21.droidtosh.ui

import dev.southstar21.droidtosh.diagnostics.ClipboardCounts
import dev.southstar21.droidtosh.diagnostics.DeviceCounts
import dev.southstar21.droidtosh.diagnostics.DiagnosticEvent
import dev.southstar21.droidtosh.diagnostics.DiagnosticsSnapshot
import dev.southstar21.droidtosh.diagnostics.DisconnectReason
import dev.southstar21.droidtosh.diagnostics.Endpoint
import dev.southstar21.droidtosh.diagnostics.EventKind
import dev.southstar21.droidtosh.diagnostics.FileCounts
import dev.southstar21.droidtosh.diagnostics.NotificationCounts
import dev.southstar21.droidtosh.diagnostics.RttSummary
import dev.southstar21.droidtosh.diagnostics.TrafficCounts
import dev.southstar21.droidtosh.files.SavedFile
import dev.southstar21.droidtosh.files.TransferDirection
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.files.TransferStatus
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.ReliableStream
import dev.southstar21.droidtosh.update.UpdateState

/** Representative state for the screenshot tests. */
object FakeUi {
    const val NOW = 1_760_000_000_000L
    private const val MINUTE = 60_000L
    private const val MB = 1_048_576L

    private val allGranted = PermissionStatus(
        notifications = true,
        phone = true,
        callLog = true,
        outgoingCalls = true,
        contacts = true,
        notificationAccess = true,
    )

    val activeTransfers = listOf(
        TransferInfo("t1", TransferDirection.OUTGOING, "Holiday photos.zip", 412 * MB, 251 * MB, TransferStatus.ACTIVE),
        TransferInfo("t2", TransferDirection.INCOMING, "Quarterly report.pdf", 8 * MB, 0, TransferStatus.QUEUED),
        TransferInfo("t3", TransferDirection.OUTGOING, "Design assets", 0, 0, TransferStatus.COMPRESSING),
    )

    val finishedTransfers = listOf(
        TransferInfo(
            "t4", TransferDirection.INCOMING, "boarding-pass.pdf", 2 * MB, 2 * MB, TransferStatus.COMPLETED,
            saved = SavedFile("boarding-pass.pdf", "content://downloads/1"),
        ),
        TransferInfo("t5", TransferDirection.OUTGOING, "IMG_2048.HEIC", 3 * MB, 3 * MB, TransferStatus.COMPLETED),
        TransferInfo("t6", TransferDirection.OUTGOING, "screen-recording.mov", 2_400 * MB, 0, TransferStatus.FAILED, error = "too_large"),
        TransferInfo("t7", TransferDirection.INCOMING, "notes.txt", 1_024, 512, TransferStatus.CANCELLED, error = "user"),
    )

    val connected = DroidUiState(
        connection = ConnectionState.Connected(
            deviceName = "Pixel 9",
            macName = "Ada’s MacBook Pro",
            macModel = "MacBook Pro 14″ · M3 Pro",
        ),
        details = ConnectionDetails(relay = "relay.example.com", stateSinceMs = NOW - 42 * MINUTE),
        lastMacName = "Ada’s MacBook Pro",
        permissions = allGranted.copy(contacts = false),
        transfers = activeTransfers.take(1),
        connectionMode = ConnectionMode.BOTH,
        versionName = "1.4.0",
        update = UpdateState.Available(version = "1.5.0", build = 340),
        nowMs = NOW,
    )

    val disconnected = connected.copy(
        connection = ConnectionState.Disconnected("Mac asleep"),
        details = ConnectionDetails(relay = "relay.example.com", stateSinceMs = NOW - 3 * 60 * MINUTE),
        permissions = allGranted.copy(notificationAccess = false, callLog = false),
        batteryOptimizationIgnored = false,
        overlayAllowed = false,
        fullScreenAllowed = false,
        transfers = emptyList(),
    )

    val connecting = connected.copy(
        connection = ConnectionState.Connecting,
        details = ConnectionDetails(relay = "relay.example.com", stateSinceMs = NOW - 5_000),
        permissions = allGranted,
        transfers = emptyList(),
    )

    val ringing = connected.copy(findRinging = true, permissions = allGranted, transfers = emptyList())

    val transfers = connected.copy(transfers = activeTransfers + finishedTransfers)

    val noTransfers = connected.copy(transfers = emptyList())

    val settingsNeedsWork = connected.copy(
        permissions = allGranted.copy(callLog = false, contacts = false),
        batteryOptimizationIgnored = false,
        overlayAllowed = false,
        fullScreenAllowed = false,
    )

    val onboardingPermissions = PermissionStatus(notifications = true, phone = false, callLog = false, contacts = true, notificationAccess = false)

    val diagnostics = DiagnosticsSnapshot(
        takenAtMs = NOW,
        state = "Connected",
        stateSinceMs = NOW - 42 * MINUTE,
        endpoint = Endpoint("relay.example.com", 443, "9F:2C:41:A8", "51:0D:EE:3B"),
        negotiatedVersion = 2,
        capabilities = listOf("notifications", "calls", "clipboard", "files", "device"),
        sessionStartedAtMs = NOW - 42 * MINUTE,
        reconnects = 3,
        reconnectReasons = mapOf(DisconnectReason.NETWORK_CHANGE to 2L, DisconnectReason.MAC_ASLEEP to 1L),
        events = listOf(
            DiagnosticEvent(NOW - 50 * MINUTE, EventKind.NETWORK, "network changed: wifi"),
            DiagnosticEvent(NOW - 43 * MINUTE, EventKind.ERROR, "relay timeout after 15 s"),
            DiagnosticEvent(NOW - 42 * MINUTE, EventKind.STATE, "connected · protocol v2"),
            DiagnosticEvent(NOW - 2 * MINUTE, EventKind.PUSH, "push wake-up received"),
        ),
        pushTokenPresent = true,
        lastPushAtMs = NOW - 2 * MINUTE,
        rtt = RttSummary(lastMs = 38, avgMs = 44, minMs = 29, maxMs = 120, samples = 214),
        sessionTraffic = TrafficCounts(envelopesIn = 1_820, envelopesOut = 1_604, bytesIn = 3 * MB, bytesOut = 412 * MB),
        totalTraffic = TrafficCounts(envelopesIn = 18_220, envelopesOut = 16_043, bytesIn = 52 * MB, bytesOut = 1_310 * MB),
        framesRejected = 0,
        inboundByFeature = mapOf(InboundFeature.NOTIFICATIONS to 640L, InboundFeature.CLIPBOARD to 12L),
        droppedByFeature = emptyMap(),
        reliable = ReliableStream.Stats(outboxSize = 2, evicted = 0, expired = 0, gaps = 1, duplicates = 0, retransmits = 4),
        clipboard = ClipboardCounts(sent = 14, received = 22, rejected = 0, interrupted = 1),
        notifications = NotificationCounts(
            forwarded = 312,
            suppressedMuted = 41,
            classifiedVoip = 3,
            textTruncated = 2,
            lastSnapshotRecords = 18,
            lastSnapshotBytes = 48_000,
            snapshotIconsDropped = 0,
            snapshotRecordsDropped = 0,
        ),
        batteryOptimizationIgnored = true,
        overlayAllowed = false,
        files = FileCounts(sent = 9, received = 4, failed = 1, resumed = 2),
        device = DeviceCounts(statusSent = 120, findsStarted = 2, findsStopped = 2, findsFailed = 0),
    )
}
