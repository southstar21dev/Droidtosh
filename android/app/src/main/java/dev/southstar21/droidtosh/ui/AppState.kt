package dev.southstar21.droidtosh.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.southstar21.droidtosh.LegacyCompanionCleanup
import dev.southstar21.droidtosh.data.CredentialStore
import dev.southstar21.droidtosh.data.PairingCredentials
import dev.southstar21.droidtosh.device.FindPhone
import dev.southstar21.droidtosh.device.PhoneStatus
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.diagnostics.DiagnosticsSharing
import dev.southstar21.droidtosh.diagnostics.DiagnosticsSnapshot
import dev.southstar21.droidtosh.files.FileTransfers
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.launch.OverlayPermission
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionService
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.transport.PairingPayloadParser
import dev.southstar21.droidtosh.update.UpdateState
import dev.southstar21.droidtosh.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionStatus(
    val notifications: Boolean = false,
    val phone: Boolean = false,
    val callLog: Boolean = false,
    val outgoingCalls: Boolean = false,
    val contacts: Boolean = false,
    val notificationAccess: Boolean = false,
) {
    val requiredGranted: Boolean
        get() = notifications && phone && notificationAccess

    val allGranted: Boolean
        get() = notifications && phone && callLog && outgoingCalls && contacts && notificationAccess
}

/** The pairing's connection mode, and whether it has a relay to choose at all. */
data class PairingMode(val mode: ConnectionMode, val relayAvailable: Boolean)

/** What Home shows under the Mac's name: the relay host and when the current state began. */
data class ConnectionDetails(val relay: String? = null, val stateSinceMs: Long? = null)

class AppState(application: Application) : AndroidViewModel(application) {
    private val credentialStore = CredentialStore(application)
    private val clipboardPreferences =
        application.getSharedPreferences("droidsync_clipboard", Context.MODE_PRIVATE)
    private val paired = MutableStateFlow(credentialStore.isPaired())
    // Read after isPaired(), which is what discards an outdated stored pairing.
    private val repairNeededState = MutableStateFlow(credentialStore.repairNeeded())
    private val permissions = MutableStateFlow(readPermissions())
    private val clipboardEnabledState =
        MutableStateFlow(clipboardPreferences.getBoolean("enabled", true))
    private val filesReceiveState = MutableStateFlow(FileTransfers.receiveEnabled(application))
    private val findEnabledState = MutableStateFlow(FindPhone.enabled(application))
    private val fullScreenAllowedState = MutableStateFlow(readFullScreenAllowed())
    private val batteryOptimizationIgnoredState = MutableStateFlow(readBatteryOptimizationIgnored())
    private val overlayAllowedState = MutableStateFlow(OverlayPermission.granted(application))
    private val pairingModeState = MutableStateFlow(readPairingMode())

    init {
        LegacyCompanionCleanup.run(application)
        if (paired.value) {
            ConnectionService.start(getApplication())
        }
    }

    val isPaired: StateFlow<Boolean> = paired

    /** True when a pairing from an older app version was discarded and the user must scan again. */
    val repairNeeded: StateFlow<Boolean> = repairNeededState.asStateFlow()
    val connectionState: StateFlow<ConnectionState> = ConnectionService.state
    val permissionStatus: StateFlow<PermissionStatus> = permissions.asStateFlow()
    val clipboardEnabled: StateFlow<Boolean> = clipboardEnabledState.asStateFlow()

    /** Whether the user turned battery optimization off for us, which keeps the connection alive in the background. */
    val batteryOptimizationIgnored: StateFlow<Boolean> = batteryOptimizationIgnoredState.asStateFlow()

    /** Whether "Display over other apps" is allowed, so VoIP calls can be answered from the Mac while unlocked. */
    val overlayAllowed: StateFlow<Boolean> = overlayAllowedState.asStateFlow()
    val filesReceiveEnabled: StateFlow<Boolean> = filesReceiveState.asStateFlow()
    val findEnabled: StateFlow<Boolean> = findEnabledState.asStateFlow()
    val pairingMode: StateFlow<PairingMode?> = pairingModeState.asStateFlow()

    /** Whether the Find my phone ring may open its screen over the lock screen (API 34+ asks the user). */
    val fullScreenAllowed: StateFlow<Boolean> = fullScreenAllowedState.asStateFlow()
    val transfers: StateFlow<List<TransferInfo>> = FileTransfers.transfers(application)
    val update: StateFlow<UpdateState> = Updates.state
    val betaUpdates: StateFlow<Boolean> = Updates.betaEnabled

    fun checkForUpdates() = Updates.checkNow(getApplication())

    fun installUpdate() = Updates.install(getApplication())

    fun setBetaUpdates(enabled: Boolean) = Updates.setBetaEnabled(getApplication(), enabled)

    /** True while the Mac is making this phone ring (Find my phone). */
    // FindPhone swaps in a new controller (and ringing flow) when the service starts, which always
    // goes with a connection-state change, so re-read it on each one.
    @OptIn(ExperimentalCoroutinesApi::class)
    val findRinging: StateFlow<Boolean> = ConnectionService.state
        .flatMapLatest { FindPhone.ringing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), FindPhone.ringing.value)

    /** The relay in use and how long the connection has been in its current state, for Home. */
    val connectionDetails: StateFlow<ConnectionDetails> = flow {
        while (true) {
            val snapshot = Diagnostics.recorder.snapshot(
                pushTokenPresent = false,
                dropped = emptyMap(),
                reliable = null,
            )
            emit(ConnectionDetails(relay = snapshot.endpoint?.host, stateSinceMs = snapshot.stateSinceMs))
            delay(DETAILS_REFRESH_MS)
        }
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), ConnectionDetails())

    fun refreshPermissions() {
        permissions.value = readPermissions()
        // A running service picks up READ_PHONE_STATE granted just now (carrier and cellular bars).
        PhoneStatus.onPermissionsChanged()
        fullScreenAllowedState.value = readFullScreenAllowed()
        batteryOptimizationIgnoredState.value = readBatteryOptimizationIgnored()
        overlayAllowedState.value = OverlayPermission.granted(getApplication())
    }

    fun setFindEnabled(enabled: Boolean) {
        findEnabledState.value = enabled
        FindPhone.setEnabled(getApplication(), enabled)
    }

    private fun readFullScreenAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getApplication<Application>().getSystemService(android.app.NotificationManager::class.java)
                .canUseFullScreenIntent()

    private fun readBatteryOptimizationIgnored(): Boolean {
        val app = getApplication<Application>()
        return app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
    }

    fun setFilesReceiveEnabled(enabled: Boolean) {
        filesReceiveState.value = enabled
        FileTransfers.setReceiveEnabled(getApplication(), enabled)
    }

    /** Queues picked documents for the Mac; returns how many could be opened. */
    fun sendFiles(uris: List<android.net.Uri>): Int = FileTransfers.send(getApplication(), uris)

    /** Zips the picked folder and queues it for the Mac. */
    fun sendFolder(uri: android.net.Uri) = FileTransfers.sendFolder(getApplication(), uri)

    fun cancelTransfer(id: String) = FileTransfers.cancel(getApplication(), id)

    fun clearFinishedTransfers() = FileTransfers.clearFinished(getApplication())

    fun setClipboardEnabled(enabled: Boolean) {
        clipboardEnabledState.value = enabled
        clipboardPreferences.edit().putBoolean("enabled", enabled).apply()
    }

    fun onPairingScanned(contents: String) {
        val payload = PairingPayloadParser.parse(contents)
        val credentials = PairingCredentials(
            payload.host,
            payload.port,
            payload.room,
            payload.fingerprint,
            payload.relayFingerprint,
            payload.psk,
            mode = payload.mode,
            localHost = payload.localHost,
            localPort = payload.localPort,
        )
        credentialStore.save(credentials)
        pairingModeState.value = PairingMode(credentials.mode, credentials.hasRelay)
        repairNeededState.value = false
        paired.value = true
        ConnectionService.start(getApplication())
    }

    /** Live diagnostics, refreshed at most 4× a second and only while the screen is observing. */
    val diagnostics: StateFlow<DiagnosticsSnapshot?> = DiagnosticsSharing.snapshots(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    /** Stops a Find my phone ring from the app, as the ringing screen's Stop button does. */
    fun stopRinging() = FindPhone.stopOnPhone()

    fun reconnectNow() {
        ConnectionService.reconnectNow(getApplication())
    }

    fun diagnosticsReport(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(DiagnosticsSharing.report(getApplication())) }
    }

    fun shareDiagnosticsLogs(onReady: (Intent) -> Unit) {
        viewModelScope.launch { onReady(DiagnosticsSharing.shareLogsIntent(getApplication())) }
    }

    /** Switches how the phone reaches the Mac and restarts the connection with it. */
    fun setConnectionMode(mode: ConnectionMode) {
        val current = pairingModeState.value ?: return
        if (current.mode == mode || (mode.usesRelay && !current.relayAvailable)) return
        viewModelScope.launch(Dispatchers.IO) {
            if (credentialStore.updateMode(mode)) {
                pairingModeState.value = current.copy(mode = mode)
                ConnectionService.restart(getApplication())
            }
        }
    }

    fun unpair() {
        ConnectionService.stop(getApplication())
        credentialStore.clear()
        pairingModeState.value = null
        paired.value = false
    }

    private fun readPairingMode(): PairingMode? =
        runCatching { credentialStore.load() }.getOrNull()?.let { PairingMode(it.mode, it.hasRelay) }

    private fun readPermissions(): PermissionStatus {
        val context = getApplication<Application>()
        return PermissionStatus(
            notifications = context.isGranted(Manifest.permission.POST_NOTIFICATIONS),
            phone = context.isGranted(Manifest.permission.READ_PHONE_STATE) &&
                context.isGranted(Manifest.permission.ANSWER_PHONE_CALLS) &&
                context.isGranted(Manifest.permission.CALL_PHONE),
            callLog = context.isGranted(Manifest.permission.READ_CALL_LOG),
            outgoingCalls = context.isGranted(Manifest.permission.PROCESS_OUTGOING_CALLS),
            contacts = context.isGranted(Manifest.permission.READ_CONTACTS),
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
        )
    }

    private companion object {
        const val DETAILS_REFRESH_MS = 1_000L
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
