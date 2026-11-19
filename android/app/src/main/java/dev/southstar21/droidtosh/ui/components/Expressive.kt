package dev.southstar21.droidtosh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * The Tether on an expressive Material shape. With [spin] the shape turns slowly (never the
 * Tether, which already moves), the way the hero cookie turned before.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeTether(
    state: TetherState,
    modifier: Modifier = Modifier,
    polygon: RoundedPolygon = MaterialShapes.Cookie12Sided,
    size: Dp = 56.dp,
    tetherSize: Dp = size * 0.62f,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    spin: Boolean = false,
    spinPeriodMs: Int = 24_000,
) {
    val shape = polygon.toShape()
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .spinning(spin, spinPeriodMs, "tether-shape-spin")
                .clip(shape)
                .background(containerColor),
        )
        Tether(state = state, size = tetherSize, color = contentColor)
    }
}

/** A tab's title: a big Jost headline with an optional line under it and an action beside it. */
@Composable
fun TabTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Spacer(Modifier.size(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The one line Home shows when something needs the user: an icon on a Sunny, the text, one action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AttentionLine(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapeIcon(
                icon = icon,
                polygon = MaterialShapes.Sunny,
                size = 40.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 40.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** One tile of a [ConnectedTiles] row. */
class TileSpec(
    val icon: ImageVector,
    val label: String,
    val polygon: RoundedPolygon,
    val onClick: () -> Unit,
)

/**
 * A connected button group of big tiles: outer corners round, inner corners tight, and each tile
 * morphs its corners when pressed (the Material 3 Expressive connected group).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedTiles(tiles: List<TileSpec>, modifier: Modifier = Modifier, height: Dp = 104.dp) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tiles.forEachIndexed { index, tile ->
            val (shape, pressed) = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShape to ButtonGroupDefaults.connectedLeadingButtonPressShape
                tiles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShape to ButtonGroupDefaults.connectedTrailingButtonPressShape
                else -> ShapeDefaults.middle to ButtonGroupDefaults.connectedMiddleButtonPressShape
            }
            FilledTonalButton(
                onClick = tile.onClick,
                shapes = ButtonDefaults.shapes(shape = shape, pressedShape = pressed),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = height),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ShapeIcon(
                        icon = tile.icon,
                        polygon = tile.polygon,
                        size = 48.dp,
                        iconSize = 22.dp,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        tile.label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Inner corners for the middle of a connected group. */
private object ShapeDefaults {
    val middle: Shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
}

/** A status card at the top of a tab: the feature on its shape, a title and one line. */
@Composable
fun FeatureStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A permission in a [ListGroup]: granted shows a check, otherwise an Allow pill. The whole row
 * opens the request either way, as Settings did.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionGroupRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    allowLabel: String,
    onClick: () -> Unit,
) {
    GroupRow(
        shape = shape,
        icon = icon,
        title = title,
        supporting = description,
        tone = if (granted) RowTone.Good else RowTone.Attention,
        onClick = onClick,
        showChevron = false,
        trailing = {
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(grantedLabel, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(allowLabel)
                }
            }
        },
    )
}
