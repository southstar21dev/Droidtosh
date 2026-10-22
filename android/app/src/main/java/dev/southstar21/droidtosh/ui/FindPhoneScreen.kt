package dev.southstar21.droidtosh.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.components.pressScale
import dev.southstar21.droidtosh.ui.theme.DroidIcons

/**
 * The Find my phone ringing screen: rings ripple out from a big, gently breathing Stop button.
 * [animate] is off in screenshot tests so the frame is deterministic.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FindPhoneScreen(onStop: () -> Unit, animate: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "find")
    val ripple = List(3) { index ->
        if (animate) {
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(2_400, easing = LinearEasing),
                    initialStartOffset = StartOffset(index * 800),
                ),
                label = "ripple-$index",
            ).value
        } else {
            (index + 1) / 3.5f
        }
    }
    val breathe = if (animate) {
        transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "breathe",
        ).value
    } else {
        1f
    }
    val spin = if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
            label = "spin",
        ).value
    } else {
        0f
    }
    val colors = MaterialTheme.colorScheme
    val stopLabel = stringResource(R.string.find_stop)

    Surface(modifier = Modifier.fillMaxSize(), color = colors.errorContainer) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 480.dp)) {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DroidIcons.Ring, contentDescription = null, tint = colors.onError, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.find_ringing_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val start = 110.dp.toPx()
                    val end = size.minDimension / 2
                    ripple.forEach { progress ->
                        drawCircle(
                            color = colors.error,
                            radius = start + (end - start) * progress,
                            center = center,
                            alpha = (1f - progress) * 0.5f,
                            style = Stroke(width = 6.dp.toPx() * (1f - progress) + 1.dp.toPx()),
                        )
                    }
                }
                val interaction = remember { MutableInteractionSource() }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp).scale(breathe)) {
                    Box(
                        Modifier
                            .size(220.dp)
                            .rotate(spin)
                            .clip(MaterialShapes.Cookie12Sided.toShape())
                            .background(colors.error),
                    )
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = colors.error,
                        contentColor = colors.onError,
                        interactionSource = interaction,
                        modifier = Modifier
                            .size(184.dp)
                            .pressScale(interaction, pressed = 0.9f)
                            .semantics {
                                role = Role.Button
                                contentDescription = stopLabel
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stopLabel, style = MaterialTheme.typography.displayMedium, color = colors.onError)
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.find_ringing_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = colors.onErrorContainer,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
