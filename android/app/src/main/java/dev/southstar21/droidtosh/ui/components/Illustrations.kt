package dev.southstar21.droidtosh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.ui.theme.Brand
import dev.southstar21.droidtosh.ui.theme.DroidIcons

@Composable
private fun spinAngle(periodMs: Int, label: String): Float {
    val transition = rememberInfiniteTransition(label = label)
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "$label-angle",
    )
    return angle
}

@Composable
private fun bob(distance: Float, periodMs: Int, label: String): Float {
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = -distance,
        targetValue = distance,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
        label = "$label-offset",
    )
    return value
}

/** Welcome art: the Droid Mac on a slowly turning cream cookie, with orbiting shapes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevicesIllustration(modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val angle = spinAngle(40_000, "devices-spin")
    val float = bob(6f, 2_400, "devices-bob")
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size * 0.82f)
                .rotate(angle)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(Brand.Cream),
        )
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = null,
            tint = Brand.DarkRoast,
            modifier = Modifier
                .size(size * 0.5f)
                .offset(y = float.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = (10 + float).dp)
                .size(size * 0.17f)
                .rotate(-angle * 2)
                .clip(MaterialShapes.Sunny.toShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 6.dp, y = (-8 - float).dp)
                .size(size * 0.14f)
                .rotate(angle * 3)
                .clip(MaterialShapes.Clover4Leaf.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 16.dp, y = 24.dp)
                .size(size * 0.07f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Pairing art: a QR code on a rounded tile with a sweeping scan line and corner brackets. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScanIllustration(modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
        label = "scan-line",
    )
    val angle = spinAngle(36_000, "scan-spin")
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .rotate(angle)
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
        )
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DroidIcons.QrCode,
                contentDescription = null,
                tint = Brand.DarkRoast, // a QR code reads dark on light in either theme
                modifier = Modifier.size(size * 0.36f),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = size * 0.56f * sweep - 2.dp)
                    .fillMaxWidth(0.86f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
