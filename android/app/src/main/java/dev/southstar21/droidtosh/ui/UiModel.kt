package dev.southstar21.droidtosh.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.update.UpdateState

/** Everything the main screens show, gathered from [AppState] so the screens stay stateless. */
@Immutable
data class DroidUiState(
    val connection: ConnectionState = ConnectionState.Connecting,
    val details: ConnectionDetails = ConnectionDetails(),
    /** The Mac's name from the last connection, kept for the hero card while disconnected. */
    val lastMacName: String = "",
    val permissions: PermissionStatus = PermissionStatus(),
    val clipboardEnabled: Boolean = true,
    val filesReceiveEnabled: Boolean = true,
    val findEnabled: Boolean = true,
    val findRinging: Boolean = false,
    val fullScreenAllowed: Boolean = true,
    val batteryOptimizationIgnored: Boolean = true,
    /** "Display over other apps", which lets the Mac answer VoIP calls while the phone is unlocked. */
    val overlayAllowed: Boolean = true,
    val transfers: List<TransferInfo> = emptyList(),
    /** The pairing's connection mode; null while unpaired or unreadable. */
    val connectionMode: ConnectionMode? = null,
    /** Whether the pairing includes a relay, so the relay modes can be chosen. */
    val relayAvailable: Boolean = true,
    val versionName: String = "",
    /** The over-the-air updater (docs/RELEASING.md). */
    val update: UpdateState = UpdateState.Idle,
    /** "Receive beta versions". */
    val betaUpdates: Boolean = false,
    /** Wall-clock time the relative labels ("for 12 min") are measured against. */
    val nowMs: Long = 0L,
) {
    val macName: String
        get() = (connection as? ConnectionState.Connected)?.macName?.takeIf { it.isNotBlank() } ?: lastMacName

    val activeTransfers: List<TransferInfo> get() = transfers.filter { !it.finished }

    /** The version an update would install, while one is on offer or under way; null otherwise. */
    val updateVersion: String?
        get() = when (val state = update) {
            is UpdateState.Available -> state.version
            is UpdateState.Downloading -> state.version
            is UpdateState.ReadyToInstall -> state.version
            is UpdateState.Installing -> state.version
            is UpdateState.Failed -> state.available?.version
            UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> null
        }

    /** Things that stop a feature from working, most important first. */
    val issues: List<Issue>
        get() = buildList {
            if (!permissions.notificationAccess) add(Issue.NotificationAccess)
            if (!permissions.notifications) add(Issue.PostNotifications)
            if (!permissions.phone) add(Issue.Phone)
            if (!batteryOptimizationIgnored) add(Issue.BatteryOptimization)
            if (findEnabled && !fullScreenAllowed) add(Issue.FullScreenIntent)
        }

    /** Optional extras that make features richer. */
    val suggestions: List<Issue>
        get() = buildList {
            if (!permissions.callLog) add(Issue.CallLog)
            if (!permissions.outgoingCalls) add(Issue.OutgoingCalls)
            if (!permissions.contacts) add(Issue.Contacts)
            if (!overlayAllowed) add(Issue.Overlay)
        }
}

enum class Issue { NotificationAccess, PostNotifications, Phone, BatteryOptimization, FullScreenIntent, CallLog, OutgoingCalls, Contacts, Overlay }

/** The app's user actions, so screens and screenshot tests can run without [AppState]. */
@Stable
class AppActions(
    val requestPermissions: (Array<String>) -> Unit = {},
    val openNotificationAccess: () -> Unit = {},
    val openBatteryOptimization: () -> Unit = {},
    val openOverlaySettings: () -> Unit = {},
    val openFullScreenSettings: () -> Unit = {},
    val sendFiles: () -> Unit = {},
    val sendFolder: () -> Unit = {},
    val sendClipboard: () -> Unit = {},
    val cancelTransfer: (String) -> Unit = {},
    val clearFinishedTransfers: () -> Unit = {},
    val setClipboardEnabled: (Boolean) -> Unit = {},
    val setFilesReceiveEnabled: (Boolean) -> Unit = {},
    val setFindEnabled: (Boolean) -> Unit = {},
    val setConnectionMode: (ConnectionMode) -> Unit = {},
    val stopRinging: () -> Unit = {},
    val reconnectNow: () -> Unit = {},
    val unpair: () -> Unit = {},
    val copyDiagnosticsReport: () -> Unit = {},
    val checkForUpdates: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val setBetaUpdates: (Boolean) -> Unit = {},
    val shareDiagnosticsLogs: () -> Unit = {},
)

/** The bottom-bar destinations. */
enum class Destination { Home, Transfers, Settings }
