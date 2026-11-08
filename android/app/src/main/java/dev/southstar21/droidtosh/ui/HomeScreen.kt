package dev.southstar21.droidtosh.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.files.TransferDirection
import dev.southstar21.droidtosh.files.TransferStatus
import dev.southstar21.droidtosh.transport.ConnectionMode
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.transport.SessionPath
import dev.southstar21.droidtosh.ui.components.BrandMark
import dev.southstar21.droidtosh.ui.components.FixItCard
import dev.southstar21.droidtosh.ui.components.PageHeader
import dev.southstar21.droidtosh.ui.components.SectionHeader
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.components.StatusChip
import dev.southstar21.droidtosh.ui.components.pressScale
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import dev.southstar21.droidtosh.update.UpdateState
import dev.southstar21.droidtosh.ui.theme.HeroTitleStyle

@Composable
fun HomeScreen(
    ui: DroidUiState,
    actions: AppActions,
    onOpenTransfers: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = stringResource(R.string.app_name),
            leading = { BrandMark(size = 44.dp) },
        )
        ConnectionHero(ui = ui, onReconnect = actions.reconnectNow)

        AnimatedVisibility(
            visible = ui.findRinging,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            RingingCard(onStop = actions.stopRinging)
        }

        SectionHeader(stringResource(R.string.home_quick_actions))
        QuickActions(actions)

        val active = ui.activeTransfers
        AnimatedVisibility(
            visible = active.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ActiveTransfersSummary(ui = ui, onClick = onOpenTransfers)
        }

        val issues = ui.issues
        if (issues.isNotEmpty()) {
            SectionHeader(pluralStringResource(R.plurals.home_needs_attention, issues.size, issues.size))
            issues.forEach { issue -> IssueCard(issue, actions) }
        }

        SectionHeader(stringResource(R.string.home_features))
        FeatureGrid(ui = ui, onOpenSettings = onOpenSettings)

        val suggestions = ui.suggestions
        // Only the offer itself; progress and errors live in Settings › Updates.
        val updateVersion = (ui.update as? UpdateState.Available)?.version
        if (suggestions.isNotEmpty() || updateVersion != null) {
            SectionHeader(stringResource(R.string.home_suggestions))
            if (updateVersion != null) SuggestionRow(updateSpec(updateVersion, actions))
            suggestions.forEach { issue -> SuggestionRow(issueSpec(issue, actions)) }
        }
    }
}

// --- Hero ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionHero(ui: DroidUiState, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val connection = ui.connection
    val connected = connection is ConnectionState.Connected
    val heroShape = MaterialTheme.shapes.extraLarge
    // Connected, the hero is the logo's own tile reversed: flat dark roast on the cream app in light
    // mode, cream on the dark roast app in dark mode. No gradients (brand rule).
    val heroColor = if (connected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh
    val background = Modifier.background(heroColor)
    val onHero = if (connected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val onHeroVariant = if (connected) onHero.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val statusText = connectionLabel(connection)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(heroShape)
            .then(background)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = statusText,
                    container = if (connected) onHero.copy(alpha = 0.14f) else statusContainer(connection),
                    content = if (connected) onHero else statusContent(connection),
                    leading = { StatusDot(connection, if (connected) onHero else statusContent(connection)) },
                )
            }
            Spacer(Modifier.size(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bouncy = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                AnimatedContent(
                    targetState = connection::class,
                    transitionSpec = {
                        (scaleIn(bouncy, initialScale = 0.6f) + fadeIn()).togetherWith(fadeOut())
                    },
                    label = "hero-shape",
                ) { kind ->
                    when (kind) {
                        // The Droid Mac on a tile in the hero's contrast colour: dark roast on cream
                        // (as on the app icon) or, on the cream hero of the dark theme, the kit's
                        // reversed tile. The cookie spins; the mark never rotates (brand rule).
                        ConnectionState.Connected::class -> ShapeIcon(
                            icon = ImageVector.vectorResource(R.drawable.ic_brand_mark),
                            polygon = MaterialShapes.Cookie9Sided,
                            size = 88.dp,
                            iconSize = 52.dp,
                            containerColor = onHero,
                            contentColor = heroColor,
                            spin = true,
                            spinPeriodMs = 20_000,
                        )
                        ConnectionState.Connecting::class -> Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(
                                modifier = Modifier.size(88.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        else -> ShapeIcon(
                            icon = DroidIcons.Laptop,
                            polygon = MaterialShapes.Ghostish,
                            size = 88.dp,
                            iconSize = 40.dp,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ui.macName.ifBlank { stringResource(R.string.home_mac_fallback) },
                        style = HeroTitleStyle,
                        color = onHero,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val model = (connection as? ConnectionState.Connected)?.macModel.orEmpty()
                    val subtitle = when (connection) {
                        is ConnectionState.Connected -> model.ifBlank { null }
                        is ConnectionState.Disconnected -> connection.reason
                        ConnectionState.Connecting -> stringResource(R.string.home_hero_connecting_detail)
                        ConnectionState.NotPaired -> null
                    }
                    if (subtitle != null) {
                        Spacer(Modifier.size(4.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = onHeroVariant)
                    }
                }
            }
            Spacer(Modifier.size(20.dp))
            HeroFacts(ui = ui, color = onHeroVariant)
            if (connection is ConnectionState.Disconnected) {
                Spacer(Modifier.size(16.dp))
                FilledTonalButton(
                    onClick = onReconnect,
                    shapes = ButtonDefaults.shapes(),
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

@Composable
private fun HeroFacts(ui: DroidUiState, color: Color) {
    val relay = ui.details.relay
    val since = ui.details.stateSinceMs
    val elapsed = if (since != null && ui.nowMs > 0) relativeDuration(ui.nowMs - since) else null
    val timeText = when {
        elapsed == null -> null
        ui.connection is ConnectionState.Connected -> stringResource(R.string.home_hero_connected_for, elapsed)
        ui.connection is ConnectionState.Disconnected -> stringResource(R.string.home_hero_last_seen, elapsed)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val viaLocal = (ui.connection as? ConnectionState.Connected)?.path == SessionPath.LOCAL
        when {
            viaLocal -> Fact(DroidIcons.Wifi, stringResource(R.string.home_hero_local), color)
            ui.connectionMode == ConnectionMode.LOCAL -> Fact(DroidIcons.Wifi, stringResource(R.string.home_hero_local_mode), color)
            else -> Fact(
                DroidIcons.Cloud,
                relay?.let { stringResource(R.string.home_hero_relay, it) } ?: stringResource(R.string.home_hero_relay_pending),
                color,
            )
        }
        if (timeText != null) Fact(DroidIcons.Clock, timeText, color)
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusDot(connection: ConnectionState, color: Color) {
    val pulse = if (connection is ConnectionState.Connected || connection is ConnectionState.Connecting) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        val value by transition.animateFloat(
            initialValue = 0.75f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "status-dot-scale",
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun statusContainer(connection: ConnectionState): Color = when (connection) {
    is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
    ConnectionState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
    is ConnectionState.Disconnected -> MaterialTheme.colorScheme.tertiaryContainer
    ConnectionState.NotPaired -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun statusContent(connection: ConnectionState): Color = when (connection) {
    is ConnectionState.Connected -> MaterialTheme.colorScheme.onPrimaryContainer
    ConnectionState.Connecting -> MaterialTheme.colorScheme.onSecondaryContainer
    is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onTertiaryContainer
    ConnectionState.NotPaired -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.NotPaired -> stringResource(R.string.home_status_not_paired)
    ConnectionState.Connecting -> stringResource(R.string.home_status_connecting)
    is ConnectionState.Connected -> stringResource(R.string.home_status_connected)
    is ConnectionState.Disconnected -> stringResource(R.string.home_status_disconnected)
}

/** "just now", "4 min", "2 h", "3 days": the coarse durations the hero card shows. */
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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = DroidIcons.Ring,
                polygon = MaterialShapes.Burst,
                size = 52.dp,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                spin = true,
                spinPeriodMs = 4_000,
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
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.find_stop)) }
        }
    }
}

// --- Quick actions ------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickActions(actions: AppActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        QuickActionTile(
            icon = DroidIcons.File,
            label = stringResource(R.string.home_quick_send_files),
            polygon = MaterialShapes.Cookie6Sided,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = actions.sendFiles,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            icon = DroidIcons.Folder,
            label = stringResource(R.string.home_quick_send_folder),
            polygon = MaterialShapes.Clover4Leaf,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = actions.sendFolder,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            icon = DroidIcons.Clipboard,
            label = stringResource(R.string.home_quick_send_clipboard),
            polygon = MaterialShapes.Pill,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = actions.sendClipboard,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    polygon: RoundedPolygon,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = modifier
            .pressScale(interaction)
            .heightIn(min = 128.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ShapeIcon(icon = icon, polygon = polygon, size = 56.dp, iconSize = 26.dp, containerColor = container, contentColor = content)
            Spacer(Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

// --- Active transfers summary -------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveTransfersSummary(ui: DroidUiState, onClick: () -> Unit) {
    val active = ui.activeTransfers
    val current = active.firstOrNull() ?: return
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (current.direction == TransferDirection.OUTGOING) DroidIcons.Upload else DroidIcons.Download,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val more = active.size - 1
                    Text(
                        text = if (more > 0) {
                            pluralStringResource(R.plurals.home_transfers_more, more, transferStatusText(current), more)
                        } else {
                            transferStatusText(current)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.home_transfers_view), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.size(14.dp))
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

// --- Fix-its ------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IssueCard(issue: Issue, actions: AppActions) {
    val spec = issueSpec(issue, actions)
    FixItCard(
        icon = spec.icon,
        title = spec.title,
        body = spec.body,
        actionLabel = spec.action,
        onAction = spec.onAction,
        polygon = spec.polygon,
    )
}

@Composable
private fun SuggestionRow(spec: IssueSpec) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = spec.onAction,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapeIcon(
                icon = spec.icon,
                polygon = spec.polygon,
                size = 40.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.titleSmall)
                Text(spec.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Text(spec.action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private class IssueSpec(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val action: String,
    val polygon: RoundedPolygon,
    val onAction: () -> Unit,
)

/** An update is on offer (docs/RELEASING.md); Install starts it, as in Settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun updateSpec(version: String, actions: AppActions) = IssueSpec(
    DroidIcons.Download,
    stringResource(R.string.update_suggestion_title, version),
    stringResource(R.string.update_suggestion_body),
    stringResource(R.string.update_suggestion_action),
    MaterialShapes.Cookie6Sided,
    actions.installUpdate,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun issueSpec(issue: Issue, actions: AppActions): IssueSpec = when (issue) {
    Issue.NotificationAccess -> IssueSpec(
        Icons.Default.Notifications,
        stringResource(R.string.fix_notification_access_title),
        stringResource(R.string.fix_notification_access_body),
        stringResource(R.string.fix_open_settings),
        MaterialShapes.SoftBurst,
        actions.openNotificationAccess,
    )
    Issue.PostNotifications -> IssueSpec(
        Icons.Default.Notifications,
        stringResource(R.string.fix_post_notifications_title),
        stringResource(R.string.fix_post_notifications_body),
        stringResource(R.string.fix_allow),
        MaterialShapes.Cookie4Sided,
    ) { actions.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) }
    Issue.Phone -> IssueSpec(
        Icons.Default.Phone,
        stringResource(R.string.fix_phone_title),
        stringResource(R.string.fix_phone_body),
        stringResource(R.string.fix_allow),
        MaterialShapes.Cookie7Sided,
    ) { actions.requestPermissions(PhonePermissions) }
    Issue.BatteryOptimization -> IssueSpec(
        DroidIcons.Battery,
        stringResource(R.string.fix_battery_title),
        stringResource(R.string.fix_battery_body),
        stringResource(R.string.fix_open_settings),
        MaterialShapes.Pill,
        actions.openBatteryOptimization,
    )
    Issue.FullScreenIntent -> IssueSpec(
        DroidIcons.Fullscreen,
        stringResource(R.string.fix_full_screen_title),
        stringResource(R.string.find_full_screen_off),
        stringResource(R.string.fix_allow),
        MaterialShapes.Square,
        actions.openFullScreenSettings,
    )
    Issue.CallLog -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_call_log_title),
        stringResource(R.string.permission_call_log_description),
        stringResource(R.string.fix_allow),
        MaterialShapes.Cookie9Sided,
    ) { actions.requestPermissions(CallLogPermissions) }
    Issue.OutgoingCalls -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_outgoing_calls_title),
        stringResource(R.string.permission_outgoing_calls_description),
        stringResource(R.string.fix_allow),
        MaterialShapes.Cookie9Sided,
    ) { actions.requestPermissions(OutgoingCallPermissions) }
    Issue.Contacts -> IssueSpec(
        Icons.Default.Person,
        stringResource(R.string.permission_contacts_title),
        stringResource(R.string.permission_contacts_description),
        stringResource(R.string.fix_allow),
        MaterialShapes.Clover4Leaf,
    ) { actions.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS)) }
    Issue.Overlay -> IssueSpec(
        Icons.Default.Call,
        stringResource(R.string.permission_overlay_title),
        stringResource(R.string.permission_overlay_description),
        stringResource(R.string.fix_allow),
        MaterialShapes.Cookie6Sided,
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

// --- Feature tiles ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeatureGrid(ui: DroidUiState, onOpenSettings: () -> Unit) {
    val on = stringResource(R.string.feature_on)
    val off = stringResource(R.string.feature_off)
    val needsAccess = stringResource(R.string.feature_needs_access)
    val tiles = buildList {
        add(
            FeatureTileSpec(
                Icons.Default.Notifications,
                stringResource(R.string.feature_notifications),
                if (ui.permissions.notificationAccess) on else needsAccess,
                ui.permissions.notificationAccess,
                MaterialShapes.Cookie9Sided,
            )
        )
        add(
            FeatureTileSpec(
                Icons.Default.Call,
                stringResource(R.string.feature_calls),
                if (ui.permissions.phone) on else needsAccess,
                ui.permissions.phone,
                MaterialShapes.Cookie7Sided,
            )
        )
        add(FeatureTileSpec(DroidIcons.Clipboard, stringResource(R.string.clipboard_title), if (ui.clipboardEnabled) on else off, ui.clipboardEnabled, MaterialShapes.Pill))
        add(
            FeatureTileSpec(
                DroidIcons.Download,
                stringResource(R.string.feature_files),
                if (ui.filesReceiveEnabled) stringResource(R.string.feature_receiving) else stringResource(R.string.feature_send_only),
                ui.filesReceiveEnabled,
                MaterialShapes.Cookie6Sided,
            )
        )
        add(
            FeatureTileSpec(
                DroidIcons.Ring,
                stringResource(R.string.feature_find),
                when {
                    !ui.findEnabled -> off
                    !ui.fullScreenAllowed -> stringResource(R.string.feature_limited)
                    else -> on
                },
                ui.findEnabled && ui.fullScreenAllowed,
                MaterialShapes.Burst,
            )
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // With an odd count the last tile spans its row instead of leaving a hole beside it.
        tiles.forEachIndexed { index, tile ->
            val wide = tiles.size % 2 == 1 && index == tiles.lastIndex
            FeatureTile(tile, onClick = onOpenSettings, modifier = Modifier.weight(1f).widthIn(min = 140.dp), wide = wide)
        }
    }
}

private class FeatureTileSpec(
    val icon: ImageVector,
    val title: String,
    val status: String,
    val ok: Boolean,
    val polygon: RoundedPolygon,
)

/** A feature's status; [wide] lays it out in one row, for a tile that has a grid row to itself. */
@Composable
private fun FeatureTile(spec: FeatureTileSpec, onClick: () -> Unit, wide: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val icon = @Composable {
        ShapeIcon(
            icon = spec.icon,
            polygon = spec.polygon,
            size = 44.dp,
            iconSize = 22.dp,
            containerColor = if (spec.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (spec.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val warning = @Composable {
        if (!spec.ok) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    val labels = @Composable {
        Text(spec.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            spec.status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (spec.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
    ) {
        if (wide) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) { labels() }
                warning()
            }
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    icon()
                    Spacer(Modifier.weight(1f))
                    warning()
                }
                Spacer(Modifier.size(14.dp))
                labels()
            }
        }
    }
}
