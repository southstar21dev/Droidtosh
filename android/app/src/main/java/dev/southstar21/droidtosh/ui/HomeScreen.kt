package dev.southstar21.droidtosh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.files.TransferDirection
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.files.TransferStatus
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.transport.SessionPath
import dev.southstar21.droidtosh.ui.components.AttentionLine
import dev.southstar21.droidtosh.ui.components.ConnectedTiles
import dev.southstar21.droidtosh.ui.components.ListGroup
import dev.southstar21.droidtosh.ui.components.SectionHeader
import dev.southstar21.droidtosh.ui.components.ShapeTether
import dev.southstar21.droidtosh.ui.components.StatusChip
import dev.southstar21.droidtosh.ui.components.SwitchRow
import dev.southstar21.droidtosh.ui.components.TetherState
import dev.southstar21.droidtosh.ui.components.TileSpec
import dev.southstar21.droidtosh.ui.components.pressScale
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import dev.southstar21.droidtosh.ui.theme.HeroTitleStyle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    ui: DroidUiState,
    actions: AppActions,
    onOpenTransfers: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = onOpenSettings,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_open_settings))
            }
        }

        MacCard(ui = ui, onOpen = onOpenConnection, onReconnect = actions.reconnectNow)

        AnimatedVisibility(
            visible = ui.findRinging,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            RingingCard(onStop = actions.stopRinging)
        }

        ui.attentions.forEach { attention -> AttentionFor(attention, actions) }

        SectionHeader(stringResource(R.string.home_quick_actions))
        ConnectedTiles(
            tiles = listOf(
                TileSpec(DroidIcons.File, stringResource(R.string.home_quick_send_files), MaterialShapes.Cookie9Sided, actions.sendFiles),
                TileSpec(DroidIcons.Folder, stringResource(R.string.home_quick_send_folder), MaterialShapes.Clover4Leaf, actions.sendFolder),
                TileSpec(DroidIcons.Clipboard, stringResource(R.string.home_quick_send_clipboard), MaterialShapes.SoftBurst, actions.sendClipboard),
            ),
        )

        ListGroup(
            title = stringResource(R.string.home_on_this_phone),
            content = {
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        icon = DroidIcons.Clipboard,
                        title = stringResource(R.string.settings_clipboard_sync),
                        supporting = stringResource(if (ui.clipboardEnabled) R.string.home_clipboard_on else R.string.home_clipboard_off),
                        checked = ui.clipboardEnabled,
                        onCheckedChange = actions.setClipboardEnabled,
                    )
                }
                item { shape ->
                    SwitchRow(
                        shape = shape,
                        icon = DroidIcons.Ring,
                        title = stringResource(R.string.find_allow),
                        supporting = stringResource(if (ui.findEnabled) R.string.home_find_on else R.string.home_find_off),
                        checked = ui.findEnabled,
                        onCheckedChange = actions.setFindEnabled,
                    )
                }
            },
            footer = stringResource(R.string.clipboard_note),
        )

        val active = ui.activeTransfers
        AnimatedVisibility(
            visible = active.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ActiveTransferCard(transfers = active, onClick = onOpenTransfers)
        }
    }
}

// --- The Mac card -------------------------------------------------------------------------

/** The Tether for a connection state: linked, reaching out, apart, or waiting to be paired. */
fun tetherFor(connection: ConnectionState): TetherState = when (connection) {
    is ConnectionState.Connected -> TetherState.Linked
    ConnectionState.Connecting -> TetherState.Handshake
    is ConnectionState.Disconnected -> TetherState.Resting
    ConnectionState.NotPaired -> TetherState.Seeking
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MacCard(ui: DroidUiState, onOpen: () -> Unit, onReconnect: () -> Unit) {
    val connection = ui.connection
    val onHero = MaterialTheme.colorScheme.inverseOnSurface
    val onHeroVariant = onHero.copy(alpha = 0.78f)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = onHero,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bouncy = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                AnimatedContent(
                    targetState = tetherFor(connection),
                    transitionSpec = { (scaleIn(bouncy, initialScale = 0.6f) + fadeIn()).togetherWith(fadeOut()) },
                    label = "mac-card-tether",
                ) { state ->
                    ShapeTether(
                        state = state,
                        polygon = MaterialShapes.Cookie12Sided,
                        size = 84.dp,
                        tetherSize = 52.dp,
                        containerColor = onHero,
                        contentColor = MaterialTheme.colorScheme.inverseSurface,
                        // Only the reaching-out states turn; a steady link holds still.
                        spin = state == TetherState.Handshake || state == TetherState.Seeking,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusChip(
                    text = connectionLabel(connection),
                    container = onHero,
                    content = MaterialTheme.colorScheme.inverseSurface,
                )
            }
            Spacer(Modifier.size(16.dp))
            Text(
                text = ui.macName.ifBlank { stringResource(R.string.home_mac_fallback) },
                style = HeroTitleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            macCardDetail(ui)?.let {
                Spacer(Modifier.size(4.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = onHeroVariant)
            }
            Spacer(Modifier.size(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modeLabel(ui),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onHeroVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.home_open_connection),
                    tint = onHeroVariant,
                )
            }
            if (connection is ConnectionState.Disconnected) {
                Spacer(Modifier.size(14.dp))
                FilledTonalButton(
                    onClick = onReconnect,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = onHero,
                        contentColor = MaterialTheme.colorScheme.inverseSurface,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.diagnostics_reconnect))
                }
            }
        }
    }
}

/** "Connected for 2 hours", "Last seen 5 min ago", or why it is down. */
@Composable
private fun macCardDetail(ui: DroidUiState): String? {
    val connection = ui.connection
    val since = ui.details.stateSinceMs
    val elapsed = if (since != null && ui.nowMs > 0) relativeDuration(ui.nowMs - since) else null
    return when (connection) {
        is ConnectionState.Connected -> elapsed?.let { stringResource(R.string.home_hero_connected_for, it) }
            ?: connection.macModel.ifBlank { null }
        is ConnectionState.Disconnected -> elapsed?.let { stringResource(R.string.home_hero_last_seen, it) } ?: connection.reason
        ConnectionState.Connecting -> stringResource(R.string.home_hero_connecting_detail)
        ConnectionState.NotPaired -> null
    }
}

/** How the phone reaches the Mac right now, in one line. */
@Composable
fun modeLabel(ui: DroidUiState): String {
    val viaLocal = (ui.connection as? ConnectionState.Connected)?.path == SessionPath.LOCAL
    return when {
        viaLocal -> stringResource(R.string.home_hero_local)
        ui.connectionMode == ConnectionMode.LOCAL -> stringResource(R.string.home_hero_local_mode)
        else -> ui.details.relay?.let { stringResource(R.string.home_hero_relay, it) }
            ?: stringResource(R.string.home_hero_relay_pending)
    }
}

@Composable
fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.NotPaired -> stringResource(R.string.home_status_not_paired)
    ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
    is ConnectionState.Connected -> stringResource(R.string.home_status_connected)
    is ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected)
}

/** "just now", "4 min", "2 h", "3 days": the coarse durations the Mac card shows. */
@Composable
fun relativeDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.duration_moments)
        minutes < 60 -> pluralStringResource(R.plurals.duration_minutes, minutes.toInt(), minutes.toInt())
        minutes < 60 * 24 -> (minutes / 60).toInt().let { pluralStringResource(R.plurals.duration_hours, it, it) }
        else -> (minutes / (60 * 24)).toInt().let { pluralStringResource(R.plurals.duration_days, it, it) }
    }
}

// --- Find my phone ringing ----------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RingingCard(onStop: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeTether(
                state = TetherState.Echo,
                polygon = MaterialShapes.Burst,
                size = 56.dp,
                tetherSize = 36.dp,
                containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                contentColor = MaterialTheme.colorScheme.inverseSurface,
                spin = true,
                spinPeriodMs = 6_000,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.find_ringing_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onStop,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseOnSurface,
                    contentColor = MaterialTheme.colorScheme.inverseSurface,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.find_stop)) }
        }
    }
}

// --- The attention line -------------------------------------------------------------------

@Composable
private fun AttentionFor(attention: Attention, actions: AppActions) {
    when (attention) {
        is Attention.Problem -> {
            val spec = issueSpec(attention.issue, actions)
            AttentionLine(icon = spec.icon, text = spec.title, actionLabel = stringResource(R.string.home_fix), onAction = spec.onAction)
        }
        is Attention.Update -> AttentionLine(
            icon = DroidIcons.Download,
            text = stringResource(R.string.home_update_ready, attention.version),
            actionLabel = stringResource(R.string.update_suggestion_action),
            onAction = actions.installUpdate,
        )
    }
}

internal class IssueSpec(
    val icon: ImageVector,
    val title: String,
    val onAction: () -> Unit,
)

@Composable
internal fun issueSpec(issue: Issue, actions: AppActions): IssueSpec = when (issue) {
    Issue.NotificationAccess -> IssueSpec(
        Icons.Default.Notifications,
        stringResource(R.string.fix_notification_access_title),
        actions.openNotificationAccess,
    )
    Issue.PostNotifications -> IssueSpec(
        Icons.Default.Notifications,
        stringResource(R.string.fix_post_notifications_title),
    ) { actions.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) }
    Issue.Phone -> IssueSpec(
        Icons.Default.Phone,
        stringResource(R.string.fix_phone_title),
    ) { actions.requestPermissions(PhonePermissions) }
    Issue.BatteryOptimization -> IssueSpec(
        DroidIcons.Battery,
        stringResource(R.string.fix_battery_title),
        actions.openBatteryOptimization,
    )
    Issue.FullScreenIntent -> IssueSpec(
        DroidIcons.Fullscreen,
        stringResource(R.string.fix_full_screen_title),
        actions.openFullScreenSettings,
    )
    Issue.CallLog -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_call_log_title),
    ) { actions.requestPermissions(CallLogPermissions) }
    Issue.OutgoingCalls -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_outgoing_calls_title),
    ) { actions.requestPermissions(OutgoingCallPermissions) }
    Issue.Contacts -> IssueSpec(
        Icons.Default.Person,
        stringResource(R.string.permission_contacts_title),
    ) { actions.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS)) }
    Issue.Overlay -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_overlay_title),
        actions.openOverlaySettings,
    )
}

val PhonePermissions = arrayOf(
    android.Manifest.permission.READ_PHONE_STATE,
    android.Manifest.permission.ANSWER_PHONE_CALLS,
    android.Manifest.permission.CALL_PHONE,
)

/**
 * The call log, plus PROCESS_OUTGOING_CALLS from the same permission group, so one prompt
 * covers both.
 */
val CallLogPermissions = arrayOf(
    android.Manifest.permission.READ_CALL_LOG,
    android.Manifest.permission.PROCESS_OUTGOING_CALLS,
)

/** The number of calls dialled on the phone; granted without a prompt once the call log is. */
val OutgoingCallPermissions = arrayOf(android.Manifest.permission.PROCESS_OUTGOING_CALLS)

// --- Active transfer ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveTransferCard(transfers: List<TransferInfo>, onClick: () -> Unit) {
    val current = transfers.firstOrNull() ?: return
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeTether(
                state = transferTether(current),
                polygon = MaterialShapes.Cookie6Sided,
                size = 48.dp,
                tetherSize = 32.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val more = transfers.size - 1
                    Text(
                        text = if (more > 0) {
                            pluralStringResource(R.plurals.home_transfers_more, more, transferPercent(current), more)
                        } else {
                            transferPercent(current)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(10.dp))
                if (current.status == TransferStatus.ACTIVE && current.size > 0) {
                    LinearWavyProgressIndicator(
                        progress = { (current.transferred.toFloat() / current.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The Tether for a transfer: zipping is a handshake, moving bytes stream toward their side. */
fun transferTether(transfer: TransferInfo): TetherState = when (transfer.status) {
    TransferStatus.COMPRESSING, TransferStatus.QUEUED, TransferStatus.WAITING -> TetherState.Handshake
    else -> TetherState.Streaming(toPhone = transfer.direction == TransferDirection.INCOMING)
}

@Composable
private fun transferPercent(transfer: TransferInfo): String =
    if (transfer.status == TransferStatus.ACTIVE && transfer.size > 0) {
        "${(transfer.transferred * 100 / transfer.size).coerceIn(0, 100)}%"
    } else {
        transferStatusText(transfer)
    }
