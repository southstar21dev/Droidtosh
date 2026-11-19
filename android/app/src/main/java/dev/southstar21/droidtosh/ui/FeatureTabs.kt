package dev.southstar21.droidtosh.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.ui.components.FeatureStatusCard
import dev.southstar21.droidtosh.ui.components.ListGroup
import dev.southstar21.droidtosh.ui.components.PermissionGroupRow
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.components.ShapeTether
import dev.southstar21.droidtosh.ui.components.TabTitle
import dev.southstar21.droidtosh.ui.components.TetherState

/** The Notifications tab: whether they reach the Mac, and the access that needs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationsScreen(
    ui: DroidUiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val granted = stringResource(R.string.permission_granted)
    val allow = stringResource(R.string.fix_allow)
    TabColumn(modifier, contentPadding) {
        TabTitle(stringResource(R.string.nav_notifications), subtitle = stringResource(R.string.notifications_subtitle))
        val access = ui.permissions.notificationAccess
        val relaying = access && ui.connection is ConnectionState.Connected
        FeatureStatusCard(
            title = stringResource(if (relaying) R.string.notifications_relaying else R.string.notifications_not_relaying),
            body = stringResource(
                when {
                    relaying -> R.string.notifications_relaying_body
                    access -> R.string.notifications_waiting_body
                    else -> R.string.notifications_not_relaying_body
                },
            ),
        ) {
            if (relaying) {
                ShapeTether(state = TetherState.Mirroring, polygon = MaterialShapes.Clover4Leaf, size = 64.dp, tetherSize = 40.dp)
            } else {
                ShapeIcon(Icons.Default.Notifications, polygon = MaterialShapes.Clover4Leaf, size = 64.dp, iconSize = 28.dp)
            }
        }
        ListGroup(
            title = stringResource(R.string.section_access),
            content = {
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Notifications,
                        stringResource(R.string.permission_notification_access_title),
                        stringResource(R.string.permission_notification_access_description),
                        ui.permissions.notificationAccess, granted, allow, actions.openNotificationAccess,
                    )
                }
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Notifications,
                        stringResource(R.string.permission_notifications_title),
                        stringResource(R.string.fix_post_notifications_body),
                        ui.permissions.notifications, granted, allow,
                    ) { actions.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                }
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Person,
                        stringResource(R.string.permission_contacts_title),
                        stringResource(R.string.permission_contacts_description),
                        ui.permissions.contacts, granted, allow,
                    ) { actions.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) }
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_mute_list_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The Calls tab: whether calls reach the Mac, and the access each part needs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CallsScreen(
    ui: DroidUiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val granted = stringResource(R.string.permission_granted)
    val allow = stringResource(R.string.fix_allow)
    TabColumn(modifier, contentPadding) {
        TabTitle(stringResource(R.string.nav_calls), subtitle = stringResource(R.string.calls_subtitle))
        val ready = ui.permissions.phone
        FeatureStatusCard(
            title = stringResource(if (ready) R.string.calls_ready else R.string.calls_not_ready),
            body = stringResource(
                when {
                    !ready -> R.string.calls_not_ready_body
                    ui.permissions.notificationAccess -> R.string.calls_ready_body
                    else -> R.string.calls_ready_cellular_body
                },
            ),
        ) {
            if (ready) {
                ShapeTether(state = TetherState.Ringing, polygon = MaterialShapes.SoftBurst, size = 64.dp, tetherSize = 40.dp)
            } else {
                ShapeIcon(Icons.Default.Phone, polygon = MaterialShapes.SoftBurst, size = 64.dp, iconSize = 28.dp)
            }
        }
        ListGroup(
            title = stringResource(R.string.section_access),
            content = {
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Phone,
                        stringResource(R.string.permission_phone_title),
                        stringResource(R.string.permission_phone_description),
                        ui.permissions.phone, granted, allow,
                    ) { actions.requestPermissions(PhonePermissions) }
                }
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Call,
                        stringResource(R.string.permission_call_log_title),
                        stringResource(R.string.permission_call_log_description),
                        ui.permissions.callLog, granted, allow,
                    ) { actions.requestPermissions(CallLogPermissions) }
                }
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Call,
                        stringResource(R.string.permission_outgoing_calls_title),
                        stringResource(R.string.permission_outgoing_calls_description),
                        ui.permissions.outgoingCalls, granted, allow,
                    ) { actions.requestPermissions(OutgoingCallPermissions) }
                }
            },
        )
        ListGroup(
            title = stringResource(R.string.calls_app_calls_header),
            content = {
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Notifications,
                        stringResource(R.string.permission_notification_access_title),
                        stringResource(R.string.permission_notification_access_description),
                        ui.permissions.notificationAccess, granted, allow, actions.openNotificationAccess,
                    )
                }
                item { shape ->
                    PermissionGroupRow(
                        shape, Icons.Default.Call,
                        stringResource(R.string.permission_overlay_title),
                        stringResource(if (ui.overlayAllowed) R.string.settings_overlay_on else R.string.permission_overlay_description),
                        ui.overlayAllowed, granted, allow, actions.openOverlaySettings,
                    )
                }
            },
        )
    }
}

/** The scrolling column every tab sits in. */
@Composable
internal fun TabColumn(
    modifier: Modifier,
    contentPadding: PaddingValues,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}
