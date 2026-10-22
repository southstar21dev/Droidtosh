package dev.southstar21.droidtosh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.diagnostics.DiagnosticsReport
import dev.southstar21.droidtosh.files.FileTransfers
import dev.southstar21.droidtosh.files.TransferDirection
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.files.TransferStatus
import dev.southstar21.droidtosh.ui.components.EmptyState
import dev.southstar21.droidtosh.ui.components.PageHeader
import dev.southstar21.droidtosh.ui.components.SectionHeader
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.components.groupItemShape
import dev.southstar21.droidtosh.ui.theme.DroidIcons

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransfersScreen(
    ui: DroidUiState,
    actions: AppActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val active = ui.transfers.filter { !it.finished }
    val finished = ui.transfers.filter { it.finished }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                PageHeader(
                    title = stringResource(R.string.transfers_title),
                    subtitle = if (ui.filesReceiveEnabled) {
                        stringResource(R.string.transfers_subtitle_receiving)
                    } else {
                        stringResource(R.string.transfers_subtitle_send_only)
                    },
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.sendFiles,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(DroidIcons.File, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.transfers_send_files), maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = actions.sendFolder,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(DroidIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.files_send_folder), maxLines = 1)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }

        if (ui.transfers.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = DroidIcons.Transfers,
                    title = stringResource(R.string.transfers_empty_title),
                    body = stringResource(R.string.transfers_empty_body),
                    polygon = MaterialShapes.Cookie12Sided,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }

        if (active.isNotEmpty()) {
            item(key = "active-header") {
                SectionHeader(stringResource(R.string.transfers_active), modifier = Modifier.padding(horizontal = 16.dp))
            }
            itemsIndexed(active, key = { _, t -> "a-" + t.id }) { _, transfer ->
                ActiveTransferCard(
                    transfer = transfer,
                    onCancel = { actions.cancelTransfer(transfer.id) },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .animateItem(),
                )
            }
        }

        if (finished.isNotEmpty()) {
            item(key = "recent-header") {
                SectionHeader(
                    stringResource(R.string.transfers_recent),
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp),
                ) {
                    TextButton(onClick = actions.clearFinishedTransfers, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.files_clear))
                    }
                }
            }
            itemsIndexed(finished, key = { _, t -> "f-" + t.id }) { index, transfer ->
                FinishedTransferRow(
                    transfer = transfer,
                    index = index,
                    count = finished.size,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 1.dp)
                        .animateItem(),
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.size(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActiveTransferCard(transfer: TransferInfo, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val outgoing = transfer.direction == TransferDirection.OUTGOING
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    icon = if (outgoing) DroidIcons.Upload else DroidIcons.Download,
                    polygon = if (outgoing) MaterialShapes.Cookie6Sided else MaterialShapes.Cookie9Sided,
                    size = 48.dp,
                    containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    spin = transfer.status == TransferStatus.ACTIVE,
                    spinPeriodMs = 6_000,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = transfer.saved?.displayName ?: transfer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(if (outgoing) R.string.transfers_to_mac else R.string.transfers_from_mac) +
                            " · " + transferStatusText(transfer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.files_cancel))
                }
            }
            Spacer(Modifier.size(14.dp))
            Row(Modifier.padding(end = 8.dp)) {
                if (transfer.status == TransferStatus.ACTIVE && transfer.size > 0) {
                    LinearWavyProgressIndicator(
                        progress = { (transfer.transferred.toFloat() / transfer.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun FinishedTransferRow(transfer: TransferInfo, index: Int, count: Int, modifier: Modifier = Modifier) {
    val failed = transfer.status == TransferStatus.FAILED
    val cancelled = transfer.status == TransferStatus.CANCELLED
    Surface(
        shape = groupItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    failed -> Icons.Default.Warning
                    cancelled -> Icons.Default.Close
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    failed -> MaterialTheme.colorScheme.error
                    cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = transfer.saved?.displayName ?: transfer.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = transferStatusText(transfer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (transfer.direction == TransferDirection.OUTGOING) DroidIcons.Upload else DroidIcons.Download,
                contentDescription = stringResource(
                    if (transfer.direction == TransferDirection.OUTGOING) R.string.transfers_to_mac else R.string.transfers_from_mac
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The one-line status the transfer rows and the Home summary show. */
@Composable
fun transferStatusText(transfer: TransferInfo): String = when (transfer.status) {
    TransferStatus.COMPRESSING -> stringResource(R.string.files_status_compressing)
    TransferStatus.QUEUED -> stringResource(R.string.files_status_queued)
    TransferStatus.WAITING -> stringResource(R.string.files_status_waiting)
    TransferStatus.ACTIVE -> stringResource(
        R.string.files_status_progress,
        DiagnosticsReport.bytes(transfer.transferred),
        DiagnosticsReport.bytes(transfer.size),
    )
    TransferStatus.COMPLETED ->
        if (transfer.direction == TransferDirection.OUTGOING) {
            stringResource(R.string.files_status_sent)
        } else {
            stringResource(R.string.files_status_received)
        }
    TransferStatus.CANCELLED -> stringResource(R.string.files_status_cancelled)
    TransferStatus.FAILED -> FileTransfers.failureText(transfer.error)
}
