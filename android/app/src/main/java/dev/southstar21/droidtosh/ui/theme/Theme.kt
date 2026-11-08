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
 * The Droidtosh brand, from the logo kit: the Droid Mac in dark roast on cream, flat colour only
 * (no gradients, shadows or glows), and Jost for type.
 */
object Brand {
    /** Cream #F5ECD7: the app-icon tile, light backgrounds, the mark on dark. */
    val Cream = Color(0xFFF5ECD7)

    /** Dark roast #1F1A17: the logo itself, dark backgrounds. */
    val DarkRoast = Color(0xFF1F1A17)

    /** Ring cream #E6D8B8, a deeper cream for containers on light. */
    val RingCream = Color(0xFFE6D8B8)

    /** Tan #A8946A, the quiet accent of the southstar21 family. */
    val Tan = Color(0xFFA8946A)
}

// Roast & Cream: dark roast carries the primary role on cream, and cream on dark roast. Neutrals
// are warm and taken from the cream–roast ramp; tan is the secondary accent, and a warm amber
// tertiary carries "needs attention" without the alarm of the error role.
private val LightScheme = lightColorScheme(
    primary = Brand.DarkRoast,
    onPrimary = Brand.Cream,
    primaryContainer = Brand.RingCream,
    onPrimaryContainer = Brand.DarkRoast,
    inversePrimary = Brand.RingCream,
    secondary = Color(0xFF6E5E44),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE2C8),
    onSecondaryContainer = Color(0xFF3A3129),
    tertiary = Color(0xFF8A5300),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF4A2A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFBF7EE),
    onBackground = Brand.DarkRoast,
    surface = Color(0xFFFBF7EE),
    onSurface = Brand.DarkRoast,
    surfaceVariant = Color(0xFFEFE6D3),
    onSurfaceVariant = Color(0xFF5A4F43),
    outline = Color(0xFF857867),
    outlineVariant = Color(0xFFD8CDB6),
    inverseSurface = Brand.DarkRoast,
    inverseOnSurface = Brand.Cream,
    surfaceTint = Brand.DarkRoast,
    surfaceDim = Color(0xFFE6DCC8),
    surfaceBright = Color(0xFFFBF7EE),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF8F1E3),
    surfaceContainer = Brand.Cream,
    surfaceContainerHigh = Color(0xFFF0E6CF),
    surfaceContainerHighest = Color(0xFFEADFC6),
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Cream,
    onPrimary = Brand.DarkRoast,
    primaryContainer = Color(0xFF4A3F33),
    onPrimaryContainer = Brand.Cream,
    inversePrimary = Brand.DarkRoast,
    secondary = Color(0xFFD8CDB6),
    onSecondary = Color(0xFF2A2320),
    secondaryContainer = Color(0xFF3A3129),
    onSecondaryContainer = Brand.RingCream,
    tertiary = Color(0xFFFFB960),
    onTertiary = Color(0xFF472A00),
    tertiaryContainer = Color(0xFF653F00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Brand.DarkRoast,
    onBackground = Brand.Cream,
    surface = Brand.DarkRoast,
    onSurface = Brand.Cream,
    surfaceVariant = Color(0xFF3A3129),
    onSurfaceVariant = Color(0xFFD0C5B0),
    outline = Color(0xFF9A8E7C),
    outlineVariant = Color(0xFF4A4038),
    inverseSurface = Brand.Cream,
    inverseOnSurface = Brand.DarkRoast,
    surfaceTint = Brand.Cream,
    surfaceDim = Brand.DarkRoast,
    surfaceBright = Color(0xFF453B33),
    surfaceContainerLowest = Color(0xFF171310),
    surfaceContainerLow = Color(0xFF241F1B),
    surfaceContainer = Color(0xFF2A2320),
    surfaceContainerHigh = Color(0xFF332B26),
    surfaceContainerHighest = Color(0xFF3D342D),
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
    extraLarge = RoundedCornerShape(36.dp),
)

/** Corner radii beyond the M3 scale, used by the grouped settings lists and the hero card. */
@Immutable
data class DroidtoshExtraShapes(
    val hero: RoundedCornerShape = RoundedCornerShape(36.dp),
    val groupOuter: androidx.compose.ui.unit.Dp = 24.dp,
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
 * The app always wears the brand palette: wallpaper-based dynamic colour would replace the dark
 * roast and cream with whatever the wallpaper suggests.
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
