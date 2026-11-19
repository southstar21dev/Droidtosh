package dev.southstar21.droidtosh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.ui.components.FeatureStatusCard
import dev.southstar21.droidtosh.ui.components.GroupRow
import dev.southstar21.droidtosh.ui.components.ListGroup
import dev.southstar21.droidtosh.ui.components.RowTone
import dev.southstar21.droidtosh.ui.components.SectionHeader
import dev.southstar21.droidtosh.ui.components.ShapeTether
import dev.southstar21.droidtosh.ui.components.SwitchRow
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import dev.southstar21.droidtosh.update.UpdateState
import kotlin.math.roundToInt

/** A page over the tabs: a large flexible top app bar with Back, and a scrolling body. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diagnostics_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** Settings, slimmed to what belongs to the app itself: updates and about. */
@Composable
fun SettingsScreen(
    ui: DroidUiState,
    actions: AppActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailPage(title = stringResource(R.string.settings_title), onBack = onBack, modifier = modifier) {
        UpdatesGroup(ui, actions)
        ListGroup(
            title = stringResource(R.string.settings_group_about),
            footer = stringResource(R.string.settings_moved_note),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.app_name),
                        supporting = stringResource(R.string.home_version, ui.versionName),
                    )
                }
            },
        )
    }
}

/** How the phone reaches the Mac, the relay, the phone's own side, diagnostics and unpairing. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionScreen(
    ui: DroidUiState,
    actions: AppActions,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    initialUnpairDialog: Boolean = false,
) {
    var showUnpairDialog by rememberSaveable { mutableStateOf(initialUnpairDialog) }
    DetailPage(
        title = stringResource(R.string.connection_title),
        subtitle = ui.macName.ifBlank { null },
        onBack = onBack,
        modifier = modifier,
    ) {
        FeatureStatusCard(
            title = connectionLabel(ui.connection),
            body = modeLabel(ui),
        ) {
            ShapeTether(
                state = tetherFor(ui.connection),
                polygon = MaterialShapes.Cookie12Sided,
                size = 64.dp,
                tetherSize = 40.dp,
                spin = ui.connection is ConnectionState.Connected,
            )
        }

        ui.connectionMode?.let { current ->
            ConnectionModeGroup(current, ui.relayAvailable, actions.setConnectionMode)
        }

        ListGroup(
            title = stringResource(R.string.connection_relay),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Cloud,
                        title = stringResource(R.string.connection_relay),
                        supporting = ui.relayAddress ?: stringResource(R.string.connection_no_relay),
                    )
                }
                ui.relayFingerprint?.let { fingerprint ->
                    item { shape ->
                        GroupRow(
                            shape = shape,
                            icon = DroidIcons.Shield,
                            title = stringResource(R.string.connection_fingerprint),
                            supporting = fingerprint,
                        )
                    }
                }
            },
        )

        ListGroup(
            title = stringResource(R.string.connection_phone_header),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Battery,
                        title = stringResource(R.string.home_action_battery_optimization),
                        supporting = stringResource(
                            if (ui.batteryOptimizationIgnored) R.string.settings_battery_unrestricted else R.string.settings_battery_optimized,
                        ),
                        tone = if (ui.batteryOptimizationIgnored) RowTone.Good else RowTone.Attention,
                        onClick = actions.openBatteryOptimization,
                    )
                }
                if (ui.findEnabled && !ui.fullScreenAllowed) {
                    item { shape ->
                        GroupRow(
                            shape = shape,
                            icon = DroidIcons.Fullscreen,
                            title = stringResource(R.string.find_full_screen_allow),
                            supporting = stringResource(R.string.find_full_screen_off),
                            tone = RowTone.Attention,
                            onClick = actions.openFullScreenSettings,
                        )
                    }
                }
            },
        )

        Column {
            SectionHeader(stringResource(R.string.diagnostics_title), modifier = Modifier.padding(bottom = 4.dp))
            DiagnosticsButtons(actions)
            Spacer(Modifier.size(2.dp))
            GroupRow(
                shape = RoundedCornerShape(28.dp),
                icon = DroidIcons.Pulse,
                title = stringResource(R.string.connection_all_diagnostics),
                supporting = stringResource(R.string.settings_diagnostics_summary),
                onClick = onOpenDiagnostics,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.diagnostics_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }

        OutlinedButton(
            onClick = { showUnpairDialog = true },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.connection_unpair))
        }
    }

    if (showUnpairDialog) {
        UnpairDialog(
            onConfirm = {
                showUnpairDialog = false
                actions.unpair()
            },
            onDismiss = { showUnpairDialog = false },
        )
    }
}

/** Reconnect, Copy report and Share logs as one connected button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiagnosticsButtons(actions: AppActions) {
    val buttons: List<Triple<ImageVector, Int, () -> Unit>> = listOf(
        Triple(Icons.Default.Refresh, R.string.diagnostics_reconnect, actions.reconnectNow),
        Triple(DroidIcons.Copy, R.string.diagnostics_copy_report, actions.copyDiagnosticsReport),
        Triple(Icons.Default.Share, R.string.diagnostics_share_logs, actions.shareDiagnosticsLogs),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        buttons.forEachIndexed { index, (icon, label, onClick) ->
            val shapes = when (index) {
                0 -> ButtonDefaults.shapes(
                    shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                    pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
                )
                buttons.lastIndex -> ButtonDefaults.shapes(
                    shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                    pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
                )
                else -> ButtonDefaults.shapes(
                    shape = RoundedCornerShape(10.dp),
                    pressedShape = ButtonGroupDefaults.connectedMiddleButtonPressShape,
                )
            }
            FilledTonalButton(
                onClick = onClick,
                shapes = shapes,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Over-the-air updates (docs/RELEASING.md): check, install what was found, and the beta channel. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UpdatesGroup(ui: DroidUiState, actions: AppActions) {
    val update = ui.update
    val busy = update is UpdateState.Checking || update is UpdateState.Downloading ||
        update is UpdateState.ReadyToInstall || update is UpdateState.Installing
    val status = when (update) {
        UpdateState.Idle -> stringResource(R.string.update_status_idle, ui.versionName)
        UpdateState.Checking -> stringResource(R.string.update_status_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date, ui.versionName)
        // A failed install is reported on the Install row; the offer still stands.
        is UpdateState.Failed -> if (update.available == null) {
            update.message
        } else {
            stringResource(R.string.update_status_available, update.available.version)
        }
        else -> stringResource(R.string.update_status_available, ui.updateVersion.orEmpty())
    }
    ListGroup(
        title = stringResource(R.string.settings_group_updates),
        content = {
            item { shape ->
                GroupRow(
                    shape = shape,
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.update_check),
                    supporting = status,
                    tone = if (update is UpdateState.Failed && update.available == null) RowTone.Attention else RowTone.Neutral,
                    onClick = if (busy) null else actions.checkForUpdates,
                    showChevron = false,
                    trailing = if (update is UpdateState.Checking) {
                        { LoadingIndicator(modifier = Modifier.size(32.dp)) }
                    } else {
                        null
                    },
                )
            }
            ui.updateVersion?.let { version ->
                item { shape ->
                    val supporting = when (update) {
                        is UpdateState.Downloading ->
                            stringResource(R.string.update_downloading, (update.progress * 100).roundToInt())
                        is UpdateState.ReadyToInstall, is UpdateState.Installing ->
                            stringResource(R.string.update_installing)
                        is UpdateState.Failed -> stringResource(R.string.update_retry_summary, update.message)
                        else -> stringResource(R.string.update_install_summary)
                    }
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Download,
                        title = stringResource(R.string.update_install, version),
                        supporting = supporting,
                        tone = if (update is UpdateState.Failed) RowTone.Attention else RowTone.Good,
                        onClick = if (busy) null else actions.installUpdate,
                        showChevron = false,
                        trailing = when (update) {
                            is UpdateState.Downloading -> {
                                { CircularWavyProgressIndicator(progress = { update.progress }, modifier = Modifier.size(32.dp)) }
                            }
                            is UpdateState.ReadyToInstall, is UpdateState.Installing -> {
                                { LoadingIndicator(modifier = Modifier.size(32.dp)) }
                            }
                            else -> null
                        },
                    )
                }
            }
            item { shape ->
                SwitchRow(
                    shape = shape,
                    icon = DroidIcons.Spark,
                    title = stringResource(R.string.update_beta),
                    supporting = stringResource(R.string.update_beta_summary),
                    checked = ui.betaUpdates,
                    onCheckedChange = actions.setBetaUpdates,
                )
            }
        },
    )
}

/** Relay, local network, or relay with the local network as a fallback (protocol §1.5). */
@Composable
private fun ConnectionModeGroup(
    current: ConnectionMode,
    relayAvailable: Boolean,
    onSelect: (ConnectionMode) -> Unit,
) {
    val options = listOf(
        Triple(ConnectionMode.RELAY, R.string.settings_mode_relay, R.string.settings_mode_relay_summary),
        Triple(ConnectionMode.LOCAL, R.string.settings_mode_local, R.string.settings_mode_local_summary),
        Triple(ConnectionMode.BOTH, R.string.settings_mode_both, R.string.settings_mode_both_summary),
    )
    ListGroup(
        title = stringResource(R.string.settings_group_connection_mode),
        footer = stringResource(R.string.settings_mode_note),
        content = {
            for ((mode, title, summary) in options) {
                item { shape ->
                    val enabled = relayAvailable || !mode.usesRelay
                    val selected = mode == current
                    GroupRow(
                        shape = shape,
                        icon = when (mode) {
                            ConnectionMode.RELAY -> DroidIcons.Cloud
                            ConnectionMode.LOCAL -> DroidIcons.Wifi
                            ConnectionMode.BOTH -> DroidIcons.Shield
                        },
                        title = stringResource(title),
                        supporting = stringResource(if (enabled) summary else R.string.settings_mode_needs_relay),
                        tone = if (selected) RowTone.Good else RowTone.Neutral,
                        titleColor = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = if (enabled && !selected) {
                            { onSelect(mode) }
                        } else {
                            null
                        },
                        showChevron = false,
                        trailing = {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = enabled,
                            )
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun UnpairDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.home_unpair_title)) },
        text = { Text(stringResource(R.string.home_unpair_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.home_unpair_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_unpair_cancel)) }
        },
    )
}
