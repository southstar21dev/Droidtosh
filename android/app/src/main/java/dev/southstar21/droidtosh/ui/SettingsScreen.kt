package dev.southstar21.droidtosh.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.ui.components.GroupRow
import dev.southstar21.droidtosh.ui.components.ListGroup
import dev.southstar21.droidtosh.ui.components.PageHeader
import dev.southstar21.droidtosh.ui.components.RowTone
import dev.southstar21.droidtosh.ui.components.SwitchRow
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import dev.southstar21.droidtosh.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    ui: DroidUiState,
    actions: AppActions,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    initialUnpairDialog: Boolean = false,
) {
    var showUnpairDialog by rememberSaveable { mutableStateOf(initialUnpairDialog) }
    val granted = stringResource(R.string.permission_granted)
    val notGranted = stringResource(R.string.settings_tap_to_allow)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PageHeader(title = stringResource(R.string.settings_title))

        // Connection & pairing
        ListGroup(
            title = stringResource(R.string.settings_group_connection),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Laptop,
                        title = ui.macName.ifBlank { stringResource(R.string.home_mac_fallback) },
                        supporting = connectionLabel(ui.connection) +
                            (ui.details.relay?.let { " · $it" } ?: ""),
                        tone = if (ui.connection is ConnectionState.Connected) RowTone.Good else RowTone.Neutral,
                    )
                }
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Battery,
                        title = stringResource(R.string.home_action_battery_optimization),
                        supporting = if (ui.batteryOptimizationIgnored) {
                            stringResource(R.string.settings_battery_unrestricted)
                        } else {
                            stringResource(R.string.settings_battery_optimized)
                        },
                        tone = if (ui.batteryOptimizationIgnored) RowTone.Good else RowTone.Attention,
                        onClick = actions.openBatteryOptimization,
                    )
                }
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = Icons.Default.Refresh,
                        title = stringResource(R.string.diagnostics_reconnect),
                        onClick = actions.reconnectNow,
                        showChevron = false,
                    )
                }
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.home_action_unpair),
                        supporting = stringResource(R.string.home_unpair_message),
                        tone = RowTone.Destructive,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showUnpairDialog = true },
                        showChevron = false,
                    )
                }
            },
        )

        ui.connectionMode?.let { current ->
            ConnectionModeGroup(current, ui.relayAvailable, actions.setConnectionMode)
        }

        // Notifications
        ListGroup(
            title = stringResource(R.string.settings_group_notifications),
            footer = stringResource(R.string.settings_mute_list_note),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.permission_notification_access_title),
                        supporting = if (ui.permissions.notificationAccess) {
                            stringResource(R.string.settings_notification_access_on)
                        } else {
                            stringResource(R.string.permission_notification_access_description)
                        },
                        tone = if (ui.permissions.notificationAccess) RowTone.Good else RowTone.Attention,
                        onClick = actions.openNotificationAccess,
                    )
                }
                item { shape ->
                    PermissionRow(
                        shape = shape,
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.permission_notifications_title),
                        granted = ui.permissions.notifications,
                        grantedText = granted,
                        notGrantedText = notGranted,
                        onClick = { actions.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                    )
                }
            },
        )

        // Calls
        ListGroup(
            title = stringResource(R.string.settings_group_calls),
            content = {
                item { shape ->
                    PermissionRow(
                        shape, Icons.Default.Phone, stringResource(R.string.permission_phone_title),
                        ui.permissions.phone, granted, notGranted,
                    ) { actions.requestPermissions(PhonePermissions) }
                }
                item { shape ->
                    PermissionRow(
                        shape, Icons.Default.Call, stringResource(R.string.permission_call_log_title),
                        ui.permissions.callLog, granted, notGranted,
                    ) { actions.requestPermissions(CallLogPermissions) }
                }
                item { shape ->
                    PermissionRow(
                        shape, Icons.Default.Call, stringResource(R.string.permission_outgoing_calls_title),
                        ui.permissions.outgoingCalls, granted, notGranted,
                    ) { actions.requestPermissions(OutgoingCallPermissions) }
                }
                item { shape ->
                    PermissionRow(
                        shape, Icons.Default.Person, stringResource(R.string.permission_contacts_title),
                        ui.permissions.contacts, granted, notGranted,
                    ) { actions.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) }
                }
                item { shape ->
                    PermissionRow(
                        shape, Icons.Default.Call, stringResource(R.string.settings_overlay_title),
                        ui.overlayAllowed, stringResource(R.string.settings_overlay_on),
                        stringResource(R.string.settings_overlay_off), actions.openOverlaySettings,
                    )
                }
            },
        )

        // Clipboard
        ListGroup(
            title = stringResource(R.string.clipboard_title),
            footer = stringResource(R.string.clipboard_note),
            content = {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        icon = DroidIcons.Clipboard,
                        title = stringResource(R.string.settings_clipboard_sync),
                        checked = ui.clipboardEnabled,
                        onCheckedChange = actions.setClipboardEnabled,
                    )
                }
            },
        )

        // Files
        ListGroup(
            title = stringResource(R.string.files_title),
            footer = stringResource(R.string.files_note),
            content = {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        icon = DroidIcons.Download,
                        title = stringResource(R.string.files_receive),
                        checked = ui.filesReceiveEnabled,
                        onCheckedChange = actions.setFilesReceiveEnabled,
                    )
                }
            },
        )

        // Find my phone
        ListGroup(
            title = stringResource(R.string.feature_find),
            footer = stringResource(R.string.find_note),
            content = {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        icon = DroidIcons.Ring,
                        title = stringResource(R.string.find_allow),
                        checked = ui.findEnabled,
                        onCheckedChange = actions.setFindEnabled,
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

        UpdatesGroup(ui, actions)

        // Advanced
        ListGroup(
            title = stringResource(R.string.settings_group_advanced),
            content = {
                item { shape ->
                    GroupRow(
                        shape = shape,
                        icon = DroidIcons.Pulse,
                        title = stringResource(R.string.home_action_diagnostics),
                        supporting = stringResource(R.string.settings_diagnostics_summary),
                        onClick = onOpenDiagnostics,
                    )
                }
            },
        )

        // About
        ListGroup(
            title = stringResource(R.string.settings_group_about),
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
private fun PermissionRow(
    shape: Shape,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    granted: Boolean,
    grantedText: String,
    notGrantedText: String,
    onClick: () -> Unit,
) {
    GroupRow(
        shape = shape,
        icon = icon,
        title = title,
        supporting = if (granted) grantedText else notGrantedText,
        tone = if (granted) RowTone.Good else RowTone.Attention,
        onClick = onClick,
        trailing = if (granted) {
            {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun UnpairDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.home_unpair_title)) },
        text = { Text(stringResource(R.string.home_unpair_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_unpair_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_unpair_cancel)) }
        },
    )
}
