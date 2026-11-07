package com.example.workoutpoc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Auto-Gym design tokens. One brand palette (lime on deep green-black) shared by every screen, with a light
 * counterpart that keeps the same hues at accessible contrast. Dynamic color is deliberately not used: the
 * notification and floating panel are drawn with fixed brand colors and the app must look like the same product.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFB4EE80), onPrimary = Color(0xFF1A3700),
    primaryContainer = Color(0xFF2C5110), onPrimaryContainer = Color(0xFFCDF8A8),
    secondary = Color(0xFFBDCBAF), onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3B4733), onSecondaryContainer = Color(0xFFD9E7CA),
    tertiary = Color(0xFF9FD0D6), onTertiary = Color(0xFF00363B),
    tertiaryContainer = Color(0xFF1E4D52), onTertiaryContainer = Color(0xFFBCEBF0),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101612), onBackground = Color(0xFFE1E4D9),
    surface = Color(0xFF101612), onSurface = Color(0xFFE1E4D9),
    surfaceVariant = Color(0xFF424940), onSurfaceVariant = Color(0xFFC2C9BB),
    outline = Color(0xFF8C9386), outlineVariant = Color(0xFF424940),
    inverseSurface = Color(0xFFE1E4D9), inverseOnSurface = Color(0xFF2E322B), inversePrimary = Color(0xFF3D6A1F),
    surfaceDim = Color(0xFF101612), surfaceBright = Color(0xFF353C36),
    surfaceContainerLowest = Color(0xFF0B100C), surfaceContainerLow = Color(0xFF171E19),
    surfaceContainer = Color(0xFF1C231E), surfaceContainerHigh = Color(0xFF252D27),
    surfaceContainerHighest = Color(0xFF303832),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3D6A1F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDF297), onPrimaryContainer = Color(0xFF0D2000),
    secondary = Color(0xFF55624B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7CA), onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF38656A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF0), onTertiaryContainer = Color(0xFF002023),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAF0), onBackground = Color(0xFF1A1C17),
    surface = Color(0xFFF8FAF0), onSurface = Color(0xFF1A1C17),
    surfaceVariant = Color(0xFFDFE4D7), onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF73796E), outlineVariant = Color(0xFFC3C8BB),
    inverseSurface = Color(0xFF2E322B), inverseOnSurface = Color(0xFFF0F2E7), inversePrimary = Color(0xFFA2D67E),
    surfaceDim = Color(0xFFD8DBD0), surfaceBright = Color(0xFFF8FAF0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F5EA),
    surfaceContainer = Color(0xFFECEFE4), surfaceContainerHigh = Color(0xFFE6E9DF),
    surfaceContainerHighest = Color(0xFFE1E4D9),
)

/** Semantic colors Material 3 does not name: positive outcomes and the "deload" accent used by charts. */
@Immutable
data class ExtendedColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
)

private val DarkExtended = ExtendedColors(
    success = Color(0xFFB4EE80), onSuccess = Color(0xFF1A3700),
    successContainer = Color(0xFF2C5110), onSuccessContainer = Color(0xFFCDF8A8),
    warning = Color(0xFFF2C46B), warningContainer = Color(0xFF4A3A0F), onWarningContainer = Color(0xFFFFE08F),
)
private val LightExtended = ExtendedColors(
    success = Color(0xFF3D6A1F), onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFBDF297), onSuccessContainer = Color(0xFF0D2000),
    warning = Color(0xFF7A5900), warningContainer = Color(0xFFFFDEA0), onWarningContainer = Color(0xFF261900),
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

private val Base = Typography()

/** Roboto (system sans) with a firmer hierarchy: bolder headings, tighter display numerals. */
val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge, bodyMedium = Base.bodyMedium, bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
)

/** Tabular figures so counters, loads and timers do not jitter as digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing scale (4 dp grid) shared by every screen. */
object Spacing {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp
    /** Horizontal gutter of screen content. */
    val gutter = 16.dp
}

@Composable
fun AutoGymTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (darkTheme) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides if (darkTheme) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppTheme {
    val extended: ExtendedColors @Composable get() = LocalExtendedColors.current
}
