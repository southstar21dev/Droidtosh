package dev.southstar21.droidtosh.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the Tether shows: the link between this phone and the Mac, as two points and what travels
 * between them. The same eight states as the Mac app's TetherView.
 */
@Immutable
sealed interface TetherState {
    /** Connected. A pulse circles between the two sides and each beats as it lands. */
    data object Linked : TetherState

    /** Waiting for the other side: one point calls out while the other circles in. */
    data object Seeking : TetherState

    /** Pairing or reconnecting: the two sides spin in, meet and lock. */
    data object Handshake : TetherState

    /** A file is moving, toward the phone or toward the Mac. */
    data class Streaming(val toPhone: Boolean) : TetherState

    /** A notification or a copy crosses over and fills the other side. */
    data object Mirroring : TetherState

    /** A call is coming in: the phone shakes inside its own waves. */
    data object Ringing : TetherState

    /** Find my phone: sound rolls out from the phone. */
    data object Echo : TetherState

    /** Offline: the two sides drift apart, hollow. */
    data object Resting : TetherState
}

/** A representative still, in seconds, for when animations are off. */
private val TetherState.stillTime: Double
    get() = when (this) {
        TetherState.Linked -> 0.25
        TetherState.Seeking -> 0.9
        TetherState.Handshake -> 0.7
        is TetherState.Streaming -> 0.2
        TetherState.Mirroring -> 1.25
        TetherState.Ringing -> 0.5
        TetherState.Echo -> 0.35
        TetherState.Resting -> 0.0
    }

/**
 * Droidtosh's status mark. Draws in [color] (the current content colour by default), so it works
 * on any surface, and holds a still frame when the system turns animations off.
 */
@Composable
fun Tether(
    state: TetherState,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val animate = remember(context, inspection) {
        !inspection && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    var time by remember(state) { mutableDoubleStateOf(state.stillTime) }
    if (animate) {
        LaunchedEffect(state) {
            // An infinite animation: UI tests pause it through InfiniteAnimationPolicy.
            val start = withInfiniteAnimationFrameMillis { it }
            while (true) {
                val now = withInfiniteAnimationFrameMillis { it }
                time = state.stillTime + (now - start) / 1000.0
            }
        }
    }
    Canvas(modifier = modifier.size(size).clearAndSetSemantics {}) {
        TetherRenderer(state, time, color, this).draw()
    }
}

/** Draws one frame. Coordinates follow a 64-point square, scaled to the canvas. */
private class TetherRenderer(
    private val state: TetherState,
    private val time: Double,
    private val color: Color,
    private val scope: DrawScope,
) {
    private val side = min(scope.size.width, scope.size.height)
    private val scale = side / 64f

    /** Strokes keep a readable width at every size, as the mockup's non-scaling strokes do. */
    private val stroke = max(1f, min(2f, side / 24f / scope.density)) * scope.density

    private fun at(x: Float, y: Float) = Offset(x * scale, y * scale)

    private fun phase(period: Double, delay: Double = 0.0): Double {
        val value = ((time - delay) % period) / period
        return if (value < 0) value + 1 else value
    }

    fun draw() = when (state) {
        TetherState.Linked -> linked()
        TetherState.Seeking -> seeking()
        TetherState.Handshake -> handshake()
        is TetherState.Streaming -> streaming(state.toPhone)
        TetherState.Mirroring -> mirroring()
        TetherState.Ringing -> ringing()
        TetherState.Echo -> echo()
        TetherState.Resting -> resting()
    }

    private fun dot(x: Float, y: Float, radius: Float, alpha: Float = 1f) {
        scope.drawCircle(color.copy(alpha = color.alpha * alpha.coerceIn(0f, 1f)), radius * scale, at(x, y))
    }

    private fun ring(x: Float, y: Float, radius: Float, alpha: Float = 1f, width: Float = stroke, dash: FloatArray? = null) {
        scope.drawCircle(
            color = color.copy(alpha = color.alpha * alpha.coerceIn(0f, 1f)),
            radius = radius * scale,
            center = at(x, y),
            style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = dash?.let { PathEffect.dashPathEffect(it.map { d -> d * scale }.toFloatArray()) }),
        )
    }

    private fun line(from: Offset, to: Offset, alpha: Float, dash: FloatArray? = null) {
        scope.drawLine(
            color = color.copy(alpha = color.alpha * alpha),
            start = at(from.x, from.y),
            end = at(to.x, to.y),
            strokeWidth = max(1f, stroke * 0.75f),
            cap = StrokeCap.Round,
            pathEffect = dash?.let { PathEffect.dashPathEffect(it.map { d -> d * scale }.toFloatArray()) },
        )
    }

    /** "Scale 1.4 at 0%, 1 from 18%", eased out. */
    private fun beat(p: Double): Float {
        if (p >= 0.18) return 1f
        val t = p / 0.18
        return 1f + 0.4f * (1 - t).pow(2).toFloat()
    }

    private fun easeInOut(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

    private fun easeOut(t: Double): Double = 1 - (1 - t).pow(2)

    private fun orbit(radius: Float, angle: Double) =
        Offset(32f + radius * cos(angle).toFloat(), 32f + radius * sin(angle).toFloat())

    private fun linked() {
        val period = 2.4
        ring(32f, 32f, 20f, alpha = 0.22f, width = max(1f, stroke * 0.75f))
        listOf(Triple(0.2, 1.6f, 0.28f), Triple(0.1, 2.2f, 0.55f), Triple(0.0, 3f, 1f)).forEach { (lag, radius, alpha) ->
            val position = orbit(20f, phase(period, lag) * 2 * PI)
            dot(position.x, position.y, radius, alpha)
        }
        dot(12f, 32f, 5.5f * beat(phase(period, 1.2)))
        dot(52f, 32f, 5.5f * beat(phase(period)))
    }

    private fun seeking() {
        for (delay in listOf(0.0, 1.2)) {
            val p = phase(2.4, delay)
            ring(32f, 32f, 6f * (1 + 3.4f * easeOut(p).toFloat()), alpha = 0.55f * (1 - p).toFloat())
        }
        ring(32f, 32f, 22f, alpha = 0.4f, width = max(1f, stroke * 0.75f), dash = floatArrayOf(2f, 5f))
        val position = orbit(22f, easeInOut(phase(3.2)) * 2 * PI)
        dot(position.x, position.y, 4f)
        dot(32f, 32f, 5.5f)
    }

    private fun handshake() {
        val p = phase(1.8)
        if (p > 0.4 && p < 0.8) {
            val local = (p - 0.4) / 0.4
            val alpha = if (local < 0.3) 0.65 * local / 0.3 else 0.65 * (1 - (local - 0.3) / 0.7)
            ring(32f, 32f, 14f * (0.3f + local.toFloat()), alpha = alpha.toFloat())
        }
        val angle = easeInOut(p) * 2 * PI
        val inward = (11 * (1 - cos(2 * PI * p)) / 2).toFloat()
        val left = orbit(20f - inward, angle + PI)
        val right = orbit(20f - inward, angle)
        dot(left.x, left.y, 5f)
        dot(right.x, right.y, 5f)
    }

    private fun streaming(toPhone: Boolean) {
        val spacing = 7f
        val shift = phase(0.45).toFloat() * spacing
        var x = 18f - spacing
        while (x <= 46f + spacing) {
            val position = if (toPhone) x + shift else x + spacing - shift
            if (position in 18f..46f) dot(position, 32f, 1.75f)
            x += spacing
        }
        val pulse = beat(phase(0.9))
        // The Mac is on the left and the phone on the right; the receiving side beats.
        dot(10f, 32f, 5.5f * if (toPhone) 1f else pulse)
        dot(54f, 32f, 5.5f * if (toPhone) pulse else 1f)
    }

    private fun mirroring() {
        val p = phase(1.8)
        line(Offset(12f, 32f), Offset(52f, 32f), alpha = 0.18f)
        dot(12f, 32f, 5.5f)
        ring(52f, 32f, 5f)
        val fill = when {
            p < 0.54 -> 0f
            p < 0.64 -> ((p - 0.54) / 0.1).toFloat() * 1.2f
            p < 0.72 -> 1.2f - 0.2f * ((p - 0.64) / 0.08).toFloat()
            p < 0.88 -> 1f
            else -> 1f - ((p - 0.88) / 0.12).toFloat()
        }
        if (fill > 0) dot(52f, 32f, 5.5f * fill)
        if (p < 0.58) {
            val travel = easeInOut(min(p / 0.55, 1.0)).toFloat()
            val alpha = when {
                p < 0.08 -> p / 0.08
                p > 0.55 -> 1 - (p - 0.55) / 0.03
                else -> 1.0
            }
            dot(12f + 40f * travel, 32f, 3f, alpha.toFloat())
        }
    }

    private fun ringing() {
        for (delay in listOf(0.0, 0.18, 0.36)) {
            val p = phase(1.5, delay)
            if (p >= 0.7) continue
            val t = p / 0.7
            ring(32f, 32f, 9f * (1 + 2 * easeOut(t).toFloat()), alpha = 0.7f * (1 - t).toFloat())
        }
        val p = phase(1.5)
        var angle = 0.0
        if (p < 0.45) {
            val envelope = min(p / 0.05, 1.0) * min((0.45 - p) / 0.05, 1.0)
            angle = 16 * cos(2 * PI * p / 0.1 + PI) * envelope
        }
        scope.rotate(angle.toFloat(), pivot = at(32f, 32f)) {
            drawRoundRect(
                color = color,
                topLeft = at(26.5f, 21f),
                size = Size(11f * scale, 22f * scale),
                cornerRadius = CornerRadius(3.5f * scale),
            )
        }
    }

    private fun echo() {
        dot(16f, 32f, 5.5f * beat(phase(1.4)))
        listOf(12f, 20f, 28f).forEachIndexed { index, radius ->
            val p = phase(1.4, index * 0.2)
            val alpha = when {
                p < 0.25 -> 0.12 + 0.88 * easeInOut(p / 0.25)
                p < 0.55 -> 1 - 0.88 * easeInOut((p - 0.25) / 0.3)
                else -> 0.12
            }
            val center = at(16f, 32f)
            val r = radius * scale
            scope.drawArc(
                color = color.copy(alpha = color.alpha * alpha.toFloat()),
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke * 1.2f, cap = StrokeCap.Round),
            )
        }
    }

    private fun resting() {
        line(Offset(19f, 32f), Offset(45f, 32f), alpha = 0.3f, dash = floatArrayOf(2f, 5f))
        val a = (-2.5 * cos(2 * PI * phase(4.0))).toFloat()
        val b = (-2.5 * cos(2 * PI * phase(4.0, 2.0))).toFloat()
        ring(12f, 32f + a, 5f, alpha = 0.6f)
        ring(52f, 32f + b, 5f, alpha = 0.6f)
    }
}
