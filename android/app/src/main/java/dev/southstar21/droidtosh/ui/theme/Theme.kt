package dev.southstar21.droidtosh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.droidtosh.R

/**
 * The Droidtosh brand in black and white: ink on paper, flat colour only (no gradients, shadows or
 * glows), and Jost for type. Every role below is a grey, so the app reads the same on any wallpaper.
 */
object Brand {
    /** Paper #FFFFFF: the app-icon tile, cards on light, the mark on dark. */
    val Paper = Color(0xFFFFFFFF)

    /** Ink #000000: the logo itself, the hero card on light, dark backgrounds. */
    val Ink = Color(0xFF000000)
}

// Black and white: ink carries the primary role on paper, and paper on ink. Every container is a
// grey tone, so Material 3 Expressive keeps its shapes, motion and hierarchy without any hue.
private val LightScheme = lightColorScheme(
    primary = Brand.Ink,
    onPrimary = Brand.Paper,
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Brand.Ink,
    inversePrimary = Brand.Paper,
    secondary = Color(0xFF444444),
    onSecondary = Brand.Paper,
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF2A2A2A),
    onTertiary = Brand.Paper,
    tertiaryContainer = Color(0xFFD6D6D6),
    onTertiaryContainer = Brand.Ink,
    error = Brand.Ink,
    onError = Brand.Paper,
    errorContainer = Color(0xFFE0E0E0),
    onErrorContainer = Brand.Ink,
    background = Color(0xFFF4F4F4),
    onBackground = Brand.Ink,
    surface = Color(0xFFF4F4F4),
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFFD0D0D0),
    inverseSurface = Brand.Ink,
    inverseOnSurface = Brand.Paper,
    surfaceTint = Brand.Ink,
    surfaceDim = Color(0xFFE0E0E0),
    surfaceBright = Brand.Paper,
    surfaceContainerLowest = Brand.Paper,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Brand.Paper,
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE0E0E0),
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Paper,
    onPrimary = Brand.Ink,
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Brand.Paper,
    inversePrimary = Brand.Ink,
    secondary = Color(0xFFC4C4C4),
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color(0xFFEEEEEE),
    tertiary = Color(0xFFD6D6D6),
    onTertiary = Brand.Ink,
    tertiaryContainer = Color(0xFF444444),
    onTertiaryContainer = Brand.Paper,
    error = Brand.Paper,
    onError = Brand.Ink,
    errorContainer = Color(0xFF3A3A3A),
    onErrorContainer = Brand.Paper,
    background = Color(0xFF0A0A0A),
    onBackground = Brand.Paper,
    surface = Color(0xFF0A0A0A),
    onSurface = Brand.Paper,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF3A3A3A),
    inverseSurface = Brand.Paper,
    inverseOnSurface = Brand.Ink,
    surfaceTint = Brand.Paper,
    surfaceDim = Color(0xFF0A0A0A),
    surfaceBright = Color(0xFF2E2E2E),
    surfaceContainerLowest = Brand.Ink,
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
)

/** Jost, the brand typeface (SIL OFL 1.1), as one variable font set to each weight. */
@OptIn(ExperimentalTextApi::class)
private fun jost(weight: FontWeight) =
    Font(R.font.jost, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val Jost = FontFamily(jost(FontWeight.Medium), jost(FontWeight.SemiBold), jost(FontWeight.Bold))

private val BaseTypography = Typography()

// Headlines and titles in Jost, the brand typeface, a little bolder than M3's defaults; body and
// label text stay in the system face for reading at small sizes.
private val DroidtoshTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = Jost, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge,
    bodyMedium = BaseTypography.bodyMedium,
    bodySmall = BaseTypography.bodySmall,
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = BaseTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)

private val DroidtoshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Corner radii beyond the M3 scale, used by the grouped settings lists and the hero card. */
@Immutable
data class DroidtoshExtraShapes(
    val hero: RoundedCornerShape = RoundedCornerShape(32.dp),
    val groupOuter: androidx.compose.ui.unit.Dp = 28.dp,
    val groupInner: androidx.compose.ui.unit.Dp = 6.dp,
)

val LocalExtraShapes = staticCompositionLocalOf { DroidtoshExtraShapes() }

/** A label style for the very large numerals and words on hero surfaces. */
val HeroTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    )

fun droidtoshColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) DarkScheme else LightScheme

/**
 * The app always wears black and white: wallpaper-based dynamic colour would bring in whatever hue
 * the wallpaper suggests.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroidtoshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = droidtoshColorScheme(darkTheme),
        motionScheme = MotionScheme.expressive(),
        shapes = DroidtoshShapes,
        typography = DroidtoshTypography,
        content = content,
    )
}
