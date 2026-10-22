package dev.southstar21.droidtosh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.theme.Brand
import dev.southstar21.droidtosh.ui.theme.LocalExtraShapes

/** The app mark, as on the launcher icon: the dark roast Droid Mac on a cream rounded square. */
@Composable
fun BrandMark(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brand.Cream),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            tint = Brand.DarkRoast,
            modifier = Modifier.size(size * 0.8f),
        )
    }
}

/** An icon on an expressive Material shape; [spin] slowly turns the shape (not the icon). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
    size: Dp = 48.dp,
    iconSize: Dp = size * 0.5f,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    spin: Boolean = false,
    spinPeriodMs: Int = 12_000,
) {
    val shape = polygon.toShape()
    val rotation = if (spin) {
        val transition = rememberInfiniteTransition(label = "shape-spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(spinPeriodMs, easing = LinearEasing)),
            label = "shape-angle",
        )
        angle
    } else {
        0f
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .rotate(rotation)
                .clip(shape)
                .background(containerColor),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** A large page title used at the top of each tab. */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

/** Squash-and-stretch on press, driven by the expressive spatial spring. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Shapes for a run of connected list items: big outer corners, small inner ones. */
@Composable
fun groupItemShape(index: Int, count: Int): Shape {
    val extra = LocalExtraShapes.current
    val outer = extra.groupOuter
    val inner = extra.groupInner
    return when {
        count == 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

/** Collects the rows of a [ListGroup]; each row is handed the shape for its position. */
class ListGroupScope {
    internal val items = mutableListOf<@Composable (Shape) -> Unit>()

    fun item(content: @Composable (Shape) -> Unit) {
        items += content
    }
}

/** A titled group of connected rows (the expressive "segmented" list look). */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: ListGroupScope.() -> Unit,
) {
    val items = ListGroupScope().apply(content).items
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(title, modifier = Modifier.padding(bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, item -> item(groupItemShape(index, items.size)) }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/** Visual weight of a row's leading icon. */
enum class RowTone { Neutral, Good, Attention, Destructive }

@Composable
private fun toneColors(tone: RowTone): Pair<Color, Color> = when (tone) {
    RowTone.Neutral -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    RowTone.Good -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    RowTone.Attention -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    RowTone.Destructive -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

/** One row in a [ListGroup]. Clickable when [onClick] is set; at least 64 dp tall. */
@Composable
fun GroupRow(
    shape: Shape,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    tone: RowTone = RowTone.Neutral,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val content: @Composable () -> Unit = {
        RowContent(icon, tone, title, titleColor, supporting, trailing, showChevron)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            interactionSource = interaction,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/** A [GroupRow] with a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    shape: Shape,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        RowContent(
            icon = icon,
            tone = if (checked) RowTone.Good else RowTone.Neutral,
            title = title,
            titleColor = MaterialTheme.colorScheme.onSurface,
            supporting = supporting,
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            },
            showChevron = false,
        )
    }
}

@Composable
private fun RowContent(
    icon: ImageVector?,
    tone: RowTone,
    title: String,
    titleColor: Color,
    supporting: String?,
    trailing: (@Composable () -> Unit)?,
    showChevron: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val (container, content) = toneColors(tone)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (supporting != null) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A small rounded status label. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A card that names a problem and offers the one action that fixes it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FixItCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    polygon: RoundedPolygon = MaterialShapes.SoftBurst,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ShapeIcon(
                    icon = icon,
                    polygon = polygon,
                    size = 48.dp,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(4.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            FilledTonalButton(
                onClick = onAction,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) { Text(actionLabel) }
        }
    }
}

/** A quiet, centred empty state with an expressive shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    polygon: RoundedPolygon = MaterialShapes.Cookie12Sided,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapeIcon(
            icon = icon,
            polygon = polygon,
            size = 120.dp,
            iconSize = 48.dp,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            spin = true,
            spinPeriodMs = 30_000,
        )
        Spacer(Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        content()
    }
}
