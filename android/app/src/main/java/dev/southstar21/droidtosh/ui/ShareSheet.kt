package dev.southstar21.droidtosh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.theme.DroidIcons

/** What the "Send file to Mac" share target managed to queue. */
data class ShareResult(val queued: Int, val total: Int, val names: List<String>)

/**
 * The share-sheet target: a bottom sheet confirming what was queued for the Mac. The files are
 * queued before it shows (the share grant ends with the activity); this only reports and offers
 * a way into Transfers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(
    result: ShareResult,
    onDone: () -> Unit,
    onOpenTransfers: () -> Unit,
    animateIn: Boolean = true,
) {
    val visible = remember { MutableTransitionState(!animateIn).apply { targetState = true } }
    val failed = result.queued == 0
    val message = when {
        failed -> stringResource(R.string.files_share_nothing)
        result.queued < result.total -> stringResource(R.string.files_share_partial, result.queued, result.total)
        else -> pluralStringResource(R.plurals.files_share_queued, result.queued, result.queued)
    }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(spatial) { it } + fadeIn(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    // Taps on the sheet itself shouldn't fall through to the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.size(20.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        if (failed) {
                            ShapeIcon(
                                icon = Icons.Default.Warning,
                                polygon = MaterialShapes.SoftBurst,
                                size = 64.dp,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ShapeIcon(
                                icon = DroidIcons.Upload,
                                polygon = MaterialShapes.Cookie9Sided,
                                size = 64.dp,
                                iconSize = 30.dp,
                                spin = animateIn,
                                spinPeriodMs = 6_000,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (failed) R.string.share_sheet_title_failed else R.string.share_sheet_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (result.names.isNotEmpty()) {
                        Spacer(Modifier.size(16.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            result.names.take(MAX_NAMES).forEach { name -> FileChip(name) }
                            if (result.names.size > MAX_NAMES) {
                                FileChip(stringResource(R.string.share_sheet_more, result.names.size - MAX_NAMES), icon = false)
                            }
                        }
                    }
                    Spacer(Modifier.size(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.share_sheet_done))
                        }
                        if (!failed) {
                            Button(
                                onClick = onOpenTransfers,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.share_sheet_view)) }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_NAMES = 3

@Composable
private fun FileChip(text: String, icon: Boolean = true) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(DroidIcons.File, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}
