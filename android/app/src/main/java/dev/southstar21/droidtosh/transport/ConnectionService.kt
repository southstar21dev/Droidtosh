package dev.southstar21.droidtosh.transport

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ServiceCompat
import dev.southstar21.droidtosh.BuildConfig
import dev.southstar21.droidtosh.LegacyCompanionCleanup
import dev.southstar21.droidtosh.clipboard.ClipboardSyncManager
import dev.southstar21.droidtosh.clipboard.SendClipboardActivity
import dev.southstar21.droidtosh.data.CredentialStore
import dev.southstar21.droidtosh.data.PairingCredentials
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.diagnostics.DisconnectReason
import dev.southstar21.droidtosh.protocol.v1.Hello
import dev.southstar21.droidtosh.update.Updates
import dev.southstar21.droidtosh.push.PushTokenStore
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ConnectionState {
    data object NotPaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(
        val deviceName: String,
        val macName: String = "",
        val macModel: String = "",
        val path: SessionPath = SessionPath.RELAY,
    ) : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState
}

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var stateJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var clipboardManager: ClipboardSyncManager? = null

    @Volatile
    private var notificationText = "Connecting"

    @Volatile
    private var connection: PhoneConnectionLoop? = null

    private val activeClient: ProtocolClient? get() = connection?.activeClient

    @Volatile
    private var destroyed = false

    @Volatile
    private var currentNetwork: Network? = null

    /** Bonjour discovery of the Mac, running while the pairing's mode uses the local network. */
    private val localDiscovery by lazy {
        LocalDiscovery(applicationContext) {
            connection?.signalNetwork()
            waitSignal.trySend(Unit)
        }
    }

    // One send stream per service start (protocol/PROTOCOL-v2.md §4); it carries across reconnects.
    private val reliableStream = ReliableStream()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        LegacyCompanionCleanup.run(this)
        Updates.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (clipboardManager == null) {
            clipboardManager = ClipboardSyncManager(applicationContext) {
                applicationContext
                    .getSharedPreferences("droidsync_clipboard", MODE_PRIVATE)
                    .getBoolean("enabled", true)
            }.also { it.start() }
        }
        // connectedDevice's runtime prerequisite is met by the CHANGE_NETWORK_STATE manifest permission.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(notificationText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        registerNetworkCallback()
        dev.southstar21.droidtosh.calls.CallManager.start(applicationContext)
        dev.southstar21.droidtosh.files.FileTransfers.start(applicationContext, scope)
        dev.southstar21.droidtosh.device.PhoneStatus.start(applicationContext, scope)
        dev.southstar21.droidtosh.device.FindPhone.start(applicationContext, scope)
        if (stateJob?.isActive != true) {
            stateJob = scope.launch { state.collect { diagnostics.onState(diagnosticsLabel(it)) } }
        }
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { connectionLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        clipboardManager?.stop()
        clipboardManager = null
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        localDiscovery.close()
        loopJob?.cancel()
        dev.southstar21.droidtosh.files.FileTransfers.stop()
        dev.southstar21.droidtosh.device.PhoneStatus.stop()
        dev.southstar21.droidtosh.device.FindPhone.stop()
        scope.cancel()
        // The loop is blocked in a socket read that coroutine cancellation can't interrupt.
        activeClient?.let(::closeInBackground)
        TransportHub.releaseStream(reliableStream)
        dev.southstar21.droidtosh.calls.CallManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun connectionLoop() {
        val credentialStore = CredentialStore(applicationContext)
        val pushTokens = PushTokenStore(applicationContext)
        // Start Bonjour early so the first local attempt already has results.
        runCatching { credentialStore.load() }.getOrNull()
            ?.takeIf { it.mode.usesLocal }
            ?.let { localDiscovery.start(it.fingerprint) }
        val loop = PhoneConnectionLoop(
            host = object : PhoneConnectionLoop.Host {
                override fun loadCredentials() = credentialStore.load()
                override fun fcmToken(): String? = pushTokens.token
                override fun deviceName(): String = deviceDisplayName(applicationContext)
                override fun hello(deviceName: String): Hello = Hello.newBuilder()
                    .setDeviceName(deviceName)
                    .setAndroidVersion(Build.VERSION.RELEASE ?: "unknown")
                    .setAppVersion(APP_VERSION)
                    .addAllCapabilities(Capabilities.SUPPORTED)
                    .build()
                override fun onState(state: ConnectionState, statusText: String?) {
                    mutableState.value = state
                    statusText?.let(::updateNotification)
                }
                override fun onNotPaired() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override val isDestroyed: Boolean get() = destroyed
                override suspend fun localCandidates(credentials: PairingCredentials): List<LocalEndpoint> {
                    localDiscovery.start(credentials.fingerprint)
                    // Give a just-started discovery a moment to resolve the Mac.
                    val sinceStart = System.currentTimeMillis() - localDiscovery.startedAtMs
                    if (localDiscovery.endpoints().isEmpty() && sinceStart < DISCOVERY_GRACE_MS) {
                        withTimeoutOrNull(DISCOVERY_GRACE_MS - sinceStart) {
                            while (localDiscovery.endpoints().isEmpty()) delay(100)
                        }
                    }
                    return localDiscovery.endpoints() + super.localCandidates(credentials)
                }
            },
            scope = scope,
            reliableStream = reliableStream,
            waitPolicy = WaitModePolicy(BuildConfig.FCM_ENABLED),
            waitSignal = waitSignal,
        )
        connection = loop
        loop.run()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                if (previous != null && previous != network) {
                    diagnostics.onNetworkChange("default network changed")
                    // A socket bound to the old default network would otherwise sit dead until the read timeout.
                    activeClient?.let { client ->
                        diagnostics.onSessionEnd(DisconnectReason.NETWORK_CHANGE)
                        closeInBackground(client)
                    }
                }
                connection?.signalNetwork()
                waitSignal.trySend(Unit)
            }
        }
        networkCallback = callback
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Droidtosh")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_brand_glyph)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_send),
                    "Send clipboard",
                    PendingIntent.getActivity(
                        this,
                        2,
                        Intent(this, SendClipboardActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        notificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun repostConnectionNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(notificationText))
    }

    /** Drops the current session (if any) and skips the backoff or wait so a new attempt starts now. */
    private fun forceReconnect() {
        val client = activeClient
        if (client != null) {
            diagnostics.onSessionEnd(DisconnectReason.MANUAL)
            closeInBackground(client)
        } else {
            diagnostics.onInfo("reconnect requested")
        }
        connection?.signalNetwork()
        waitSignal.trySend(Unit)
    }

    private fun closeInBackground(client: ProtocolClient) = PhoneConnectionLoop.closeInBackground(client)

    companion object {
        const val APP_VERSION = "0.1.0"
        private const val DISCOVERY_GRACE_MS = 2_000L
        private const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: ConnectionService? = null

        private val diagnostics get() = Diagnostics.recorder

        private fun diagnosticsLabel(state: ConnectionState): String = when (state) {
            ConnectionState.NotPaired -> "Not paired"
            ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Connected -> {
                val name = if (state.macName.isBlank()) "Connected" else "Connected to ${state.macName}"
                if (state.path == SessionPath.LOCAL) "$name via local network" else name
            }
            is ConnectionState.Disconnected -> "Disconnected (${state.reason})"
        }

        /** "Reconnect now" from diagnostics; starts the service if it isn't running. */
        fun reconnectNow(context: Context) {
            val service = instance
            if (service == null) start(context) else service.forceReconnect()
        }

        fun repostNotification() {
            instance?.repostConnectionNotification()
        }

        private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.NotPaired)
        val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

        private val waitSignal = Channel<Unit>(Channel.CONFLATED)

        fun wakeForMacOnline() {
            waitSignal.trySend(Unit)
        }

        /**
         * Starts the foreground service. From the background this only works while Android grants an
         * exemption (battery optimization off, high-priority FCM message, BOOT_COMPLETED, visible UI); a
         * refusal is recorded instead of crashing the caller.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ConnectionService::class.java))
            true
        } catch (error: ForegroundServiceStartNotAllowedException) {
            android.util.Log.w("DroidsyncConn", "foreground service start not allowed", error)
            diagnostics.onServiceStartBlocked(error.message ?: "not allowed from background")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }
}

private fun deviceDisplayName(context: Context): String {
    val resolver = context.contentResolver
    val candidates = listOf(
        runCatching { Settings.Global.getString(resolver, "device_name") }.getOrNull(),
        runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull(),
        Build.MODEL,
    )
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Android"
}
