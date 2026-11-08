package dev.southstar21.droidtosh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.droidtosh.BuildConfig
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.clipboard.SendClipboardActivity
import dev.southstar21.droidtosh.launch.OverlayPermission
import dev.southstar21.droidtosh.transport.ConnectionState
import androidx.compose.foundation.layout.Arrangement
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** The paired app: Home, Transfers and Settings, with Diagnostics as a detail page of Settings. */
@Composable
fun MainShell(
    state: AppState,
    requestedDestination: Destination?,
    onDestinationHandled: () -> Unit,
) {
    val connection by state.connectionState.collectAsStateWithLifecycle()
    val details by state.connectionDetails.collectAsStateWithLifecycle()
    val permissions by state.permissionStatus.collectAsStateWithLifecycle()
    val clipboardEnabled by state.clipboardEnabled.collectAsStateWithLifecycle()
    val batteryOptimizationIgnored by state.batteryOptimizationIgnored.collectAsStateWithLifecycle()
    val overlayAllowed by state.overlayAllowed.collectAsStateWithLifecycle()
    val filesReceiveEnabled by state.filesReceiveEnabled.collectAsStateWithLifecycle()
    val transfers by state.transfers.collectAsStateWithLifecycle()
    val findEnabled by state.findEnabled.collectAsStateWithLifecycle()
    val findRinging by state.findRinging.collectAsStateWithLifecycle()
    val fullScreenAllowed by state.fullScreenAllowed.collectAsStateWithLifecycle()
    val pairingMode by state.pairingMode.collectAsStateWithLifecycle()
    val update by state.update.collectAsStateWithLifecycle()
    val betaUpdates by state.betaUpdates.collectAsStateWithLifecycle()
    var lastMacName by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(connection) {
        (connection as? ConnectionState.Connected)?.macName?.takeIf { it.isNotBlank() }?.let { lastMacName = it }
    }
    // Relative labels ("for 12 min") only need minute precision.
    val now by produceState(System.currentTimeMillis(), details) {
        value = System.currentTimeMillis()
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state.refreshPermissions()
    }

    val ui = DroidUiState(
        connection = connection,
        details = details,
        lastMacName = lastMacName,
        permissions = permissions,
        clipboardEnabled = clipboardEnabled,
        filesReceiveEnabled = filesReceiveEnabled,
        findEnabled = findEnabled,
        findRinging = findRinging,
        fullScreenAllowed = fullScreenAllowed,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        overlayAllowed = overlayAllowed,
        transfers = transfers,
        connectionMode = pairingMode?.mode,
        relayAvailable = pairingMode?.relayAvailable ?: true,
        versionName = BuildConfig.VERSION_NAME,
        update = update,
        betaUpdates = betaUpdates,
        nowMs = now,
    )
    val actions = rememberAppActions(state)

    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(requestedDestination) {
        if (requestedDestination != null) {
            destination = requestedDestination
            showDiagnostics = false
            onDestinationHandled()
        }
    }

    MainScaffold(
        ui = ui,
        actions = actions,
        destination = destination,
        onDestinationChange = { destination = it },
        showDiagnostics = showDiagnostics,
        onShowDiagnosticsChange = { showDiagnostics = it },
        diagnostics = {
            val snapshot by state.diagnostics.collectAsStateWithLifecycle()
            DiagnosticsScreen(snapshot = snapshot, actions = actions, onBack = { showDiagnostics = false })
        },
    )
}

@Composable
private fun rememberAppActions(state: AppState): AppActions {
    val context = LocalContext.current
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { state.refreshPermissions() }
    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) state.sendFiles(uris) }
    val pickFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) state.sendFolder(uri) }

    return remember(state, context) {
        AppActions(
            requestPermissions = { permissionsLauncher.launch(it) },
            openNotificationAccess = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            openBatteryOptimization = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            openOverlaySettings = { context.startActivity(OverlayPermission.settingsIntent(context)) },
            openFullScreenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            sendFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
            sendFolder = { pickFolderLauncher.launch(null) },
            // The same path as the connection notification's "Send clipboard" action.
            sendClipboard = { context.startActivity(Intent(context, SendClipboardActivity::class.java)) },
            cancelTransfer = state::cancelTransfer,
            clearFinishedTransfers = state::clearFinishedTransfers,
            setClipboardEnabled = state::setClipboardEnabled,
            setFilesReceiveEnabled = state::setFilesReceiveEnabled,
            setFindEnabled = state::setFindEnabled,
            setConnectionMode = state::setConnectionMode,
            stopRinging = state::stopRinging,
            reconnectNow = state::reconnectNow,
            unpair = state::unpair,
            copyDiagnosticsReport = {
                state.diagnosticsReport { report ->
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Droidtosh diagnostics", report))
                    Toast.makeText(context, R.string.diagnostics_report_copied, Toast.LENGTH_SHORT).show()
                }
            },
            shareDiagnosticsLogs = { state.shareDiagnosticsLogs { intent -> context.startActivity(intent) } },
            checkForUpdates = state::checkForUpdates,
            installUpdate = state::installUpdate,
            setBetaUpdates = state::setBetaUpdates,
        )
    }
}

private val WideLayoutMinWidth = 600.dp

/** Stateless shell: adaptive navigation (bottom bar or rail) and the Diagnostics detail page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScaffold(
    ui: DroidUiState,
    actions: AppActions,
    destination: Destination,
    onDestinationChange: (Destination) -> Unit,
    showDiagnostics: Boolean,
    onShowDiagnosticsChange: (Boolean) -> Unit,
    diagnostics: @Composable () -> Unit,
) {
    BackHandler(enabled = destination != Destination.Home && !showDiagnostics) {
        onDestinationChange(Destination.Home)
    }
    val activeCount = ui.activeTransfers.size

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideLayoutMinWidth
        val content: @Composable (PaddingValues) -> Unit = { padding ->
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    (fadeIn(effects) + scaleIn(spatial, initialScale = 0.96f)).togetherWith(fadeOut(fastEffects))
                },
                label = "destination",
            ) { target ->
                val screenModifier = Modifier
                    .fillMaxSize()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 720.dp)
                when (target) {
                    Destination.Home -> HomeScreen(
                        ui = ui,
                        actions = actions,
                        onOpenTransfers = { onDestinationChange(Destination.Transfers) },
                        onOpenSettings = { onDestinationChange(Destination.Settings) },
                        modifier = screenModifier,
                        contentPadding = padding,
                    )
                    Destination.Transfers -> TransfersScreen(ui, actions, screenModifier, padding)
                    Destination.Settings -> SettingsScreen(
                        ui = ui,
                        actions = actions,
                        onOpenDiagnostics = { onShowDiagnosticsChange(true) },
                        modifier = screenModifier,
                        contentPadding = padding,
                    )
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                WideNavigationRail(
                    // The page header already carries the brand mark; keep the rail to navigation.
                    arrangement = Arrangement.Center,
                ) {
                    Destination.entries.forEach { item ->
                        WideNavigationRailItem(
                            selected = destination == item,
                            onClick = { onDestinationChange(item) },
                            icon = { NavIcon(item, activeCount) },
                            label = { Text(destinationLabel(item)) },
                            railExpanded = false,
                        )
                    }
                }
                Scaffold(modifier = Modifier.weight(1f)) { padding -> content(padding) }
            }
        } else {
            Scaffold(
                bottomBar = {
                    ShortNavigationBar {
                        Destination.entries.forEach { item ->
                            ShortNavigationBarItem(
                                selected = destination == item,
                                onClick = { onDestinationChange(item) },
                                icon = { NavIcon(item, activeCount) },
                                label = { Text(destinationLabel(item)) },
                            )
                        }
                    }
                },
            ) { padding -> content(padding) }
        }
    }

    DiagnosticsOverlay(visible = showDiagnostics, onDismiss = { onShowDiagnosticsChange(false) }, content = diagnostics)
}

/** Diagnostics slides over the shell and follows the predictive-back gesture as it goes. */
@Composable
private fun DiagnosticsOverlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = visible) { events: Flow<BackEventCompat> ->
        try {
            events.collect { event ->
                backProgress = event.progress
                swipeEdge = event.swipeEdge
            }
            onDismiss()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    LaunchedEffect(visible) { if (visible) backProgress = 0f }
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 4 } + fadeIn(),
        exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 4 } + fadeOut(),
    ) {
        val shift = with(density) { 24.dp.toPx() } * backProgress * if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - 0.08f * backProgress
                    scaleX = scale
                    scaleY = scale
                    translationX = shift
                }
                .clip(MaterialTheme.shapes.extraLarge.takeIf { backProgress > 0f } ?: androidx.compose.ui.graphics.RectangleShape),
            color = MaterialTheme.colorScheme.surface,
        ) { content() }
    }
}

@Composable
private fun NavIcon(destination: Destination, activeTransfers: Int) {
    val icon: ImageVector = when (destination) {
        Destination.Home -> Icons.Default.Home
        Destination.Transfers -> DroidIcons.Transfers
        Destination.Settings -> Icons.Default.Settings
    }
    if (destination == Destination.Transfers && activeTransfers > 0) {
        BadgedBox(badge = { Badge { Text(activeTransfers.toString()) } }) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

@Composable
private fun destinationLabel(destination: Destination): String = when (destination) {
    Destination.Home -> stringResource(R.string.nav_home)
    Destination.Transfers -> stringResource(R.string.nav_transfers)
    Destination.Settings -> stringResource(R.string.nav_settings)
}
