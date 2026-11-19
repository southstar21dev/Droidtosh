package dev.southstar21.droidtosh.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.southstar21.droidtosh.background.BackgroundCheck
import dev.southstar21.droidtosh.background.BackgroundHealth
import dev.southstar21.droidtosh.background.BackgroundInputs
import dev.southstar21.droidtosh.background.OemVendor
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.push.WakeProvider
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
    /** The manufacturer whose battery manager can kill the app; null on a phone without one. */
    val oemVendor: OemVendor? = null,
    /** The user went through the manufacturer's battery settings. */
    val vendorBatteryHandled: Boolean = false,
    /** "Display over other apps", which lets the Mac answer VoIP calls while the phone is unlocked. */
    val overlayAllowed: Boolean = true,
    val transfers: List<TransferInfo> = emptyList(),
    /** The pairing's connection mode; null while unpaired or unreadable. */
    val connectionMode: ConnectionMode? = null,
    /** Whether the pairing includes a relay, so the relay modes can be chosen. */
    val relayAvailable: Boolean = true,
    /** The relay from the pairing ("relay.example.com:443"); null when the pairing has none. */
    val relayAddress: String? = null,
    /** The pinned relay certificate's fingerprint, shortened for display; null without a relay. */
    val relayFingerprint: String? = null,
    /** The wake-up choices on the Connection page; null while unpaired (nothing to show). */
    val wake: WakeUiState? = null,
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

    /** What Home's attention lines ask about: the most important problem, then any update on offer. */
    val attentions: List<Attention>
        get() = buildList {
            issues.firstOrNull()?.let { add(Attention.Problem(it)) }
            val offered = when (val state = update) {
                is UpdateState.Available -> state.version
                is UpdateState.ReadyToInstall -> state.version
                is UpdateState.Failed -> state.available?.version
                else -> null
            }
            offered?.let { add(Attention.Update(it)) }
        }

    /** The inputs of the Background checklist; the Connection page and Home's attention line both read it. */
    val backgroundInputs: BackgroundInputs
        get() = BackgroundInputs(
            notificationAccess = permissions.notificationAccess,
            postNotifications = permissions.notifications,
            batteryIgnored = batteryOptimizationIgnored,
            vendor = oemVendor,
            vendorHandled = vendorBatteryHandled,
        )

    /** Things that stop a feature from working, most important first. */
    val issues: List<Issue>
        get() {
            val failing = BackgroundHealth.failing(backgroundInputs).toSet()
            return buildList {
                if (BackgroundCheck.NotificationAccess in failing) add(Issue.NotificationAccess)
                if (BackgroundCheck.PostNotifications in failing) add(Issue.PostNotifications)
                if (!permissions.phone) add(Issue.Phone)
                if (BackgroundCheck.BatteryOptimization in failing) add(Issue.BatteryOptimization)
                if (BackgroundCheck.VendorBattery in failing) add(Issue.VendorBattery)
                if (findEnabled && !fullScreenAllowed) add(Issue.FullScreenIntent)
            }
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

/** What Home's single attention line is about. */
sealed interface Attention {
    data class Problem(val issue: Issue) : Attention
    data class Update(val version: String) : Attention
}

enum class Issue { NotificationAccess, PostNotifications, Phone, BatteryOptimization, VendorBattery, FullScreenIntent, CallLog, OutgoingCalls, Contacts, Overlay }

/** The app's user actions, so screens and screenshot tests can run without [AppState]. */
@Stable
class AppActions(
    val requestPermissions: (Array<String>) -> Unit = {},
    val openNotificationAccess: () -> Unit = {},
    val openBatteryOptimization: () -> Unit = {},
    val openVendorBattery: () -> Unit = {},
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
    val setWakeProvider: (WakeProvider) -> Unit = {},
    val pickDistributor: () -> Unit = {},
    val stopRinging: () -> Unit = {},
    val reconnectNow: () -> Unit = {},
    val unpair: () -> Unit = {},
    val copyDiagnosticsReport: () -> Unit = {},
    val checkForUpdates: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val setBetaUpdates: (Boolean) -> Unit = {},
    val shareDiagnosticsLogs: () -> Unit = {},
)

/** The wake-up providers that can be chosen here, the one in use, and whether UnifiedPush has an endpoint. */
data class WakeUiState(
    val options: List<WakeProvider>,
    val selected: WakeProvider,
    val unifiedPushRegistered: Boolean = false,
)

/** The bottom-bar tabs. [Transfers] is the Files tab (its name is kept for notification intents). */
enum class Destination { Home, Transfers, Notifications, Calls }

/** Pages that open over the tabs: the connection (from the Mac card), Settings, and Diagnostics. */
enum class Page { Connection, Settings, Diagnostics }
