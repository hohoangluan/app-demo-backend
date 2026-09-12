package com.youreyes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour roles.
 *
 * Screens ask for a *role* — "this is secondary text", "this is a card surface" —
 * rather than naming a hex token, which is what lets high-contrast mode swap the
 * entire palette without every screen knowing it exists. The raw tokens in
 * `Color.kt` stay public for the few places that need a literal brand colour.
 */
@Immutable
data class YourEyesColors(
    val ground: Brush,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkDisabled: Color,
    val accent: Color,
    val accentText: Color,
    val accentSoft: Color,
    val border: Color,
    val line: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onAccent: Color,
    val primaryButton: Brush,
    val isHighContrast: Boolean,
)

private val LightColors = YourEyesColors(
    ground = Brush.verticalGradient(
        listOf(YourEyesGradientStart, YourEyesGradientMiddle, YourEyesGradientEnd),
    ),
    surface = YourEyesSurface,
    surfaceSunken = YourEyesSurfaceSunken,
    ink = YourEyesInk,
    inkSecondary = YourEyesMuted,
    inkDisabled = YourEyesDisabled,
    accent = YourEyesCyan,
    accentText = YourEyesCyanText,
    accentSoft = YourEyesMintSoft,
    border = YourEyesBorder,
    line = YourEyesLine,
    success = YourEyesSuccess,
    successSoft = YourEyesSuccessSoft,
    warning = YourEyesWarning,
    warningSoft = YourEyesWarningSoft,
    danger = YourEyesDanger,
    dangerSoft = YourEyesDangerSoft,
    onAccent = Color.White,
    primaryButton = Brush.horizontalGradient(
        listOf(YourEyesButtonGradientStart, YourEyesButtonGradientEnd),
    ),
    isHighContrast = false,
)

/**
 * High contrast drops every gradient. A gradient is a range of contrast ratios,
 * and this mode exists precisely so that no piece of text sits at an unknown
 * one — so the ground is a flat near-black and the primary button is a flat
 * amber with black lettering (12.6:1).
 */
private val HighContrastColors = YourEyesColors(
    ground = Brush.verticalGradient(listOf(HcGround, HcGround)),
    surface = HcSurface,
    surfaceSunken = HcSurfaceSunken,
    ink = HcInk,
    inkSecondary = HcMuted,
    inkDisabled = HcBorder,
    accent = HcAccent,
    accentText = HcAccent,
    accentSoft = HcSurfaceSunken,
    border = HcBorder,
    line = HcBorder,
    success = HcSuccess,
    successSoft = HcSurfaceSunken,
    warning = HcWarning,
    warningSoft = HcSurfaceSunken,
    danger = HcDanger,
    dangerSoft = HcSurfaceSunken,
    onAccent = HcGround,
    primaryButton = Brush.horizontalGradient(listOf(HcAccent, HcAccent)),
    isHighContrast = true,
)

private val LightColorScheme = lightColorScheme(
    primary = YourEyesCyanText,
    onPrimary = YourEyesSurface,
    primaryContainer = YourEyesMintSoft,
    onPrimaryContainer = YourEyesNavy,
    secondary = YourEyesTealText,
    onSecondary = YourEyesSurface,
    background = YourEyesGradientStart,
    onBackground = YourEyesInk,
    surface = YourEyesSurface,
    onSurface = YourEyesInk,
    surfaceVariant = YourEyesMintSoft,
    onSurfaceVariant = YourEyesMuted,
    outline = YourEyesBorder,
    error = YourEyesDanger,
    onError = YourEyesSurface,
)

private val HighContrastColorScheme = lightColorScheme(
    primary = HcAccent,
    onPrimary = HcGround,
    primaryContainer = HcSurfaceSunken,
    onPrimaryContainer = HcInk,
    secondary = HcAccent,
    onSecondary = HcGround,
    background = HcGround,
    onBackground = HcInk,
    surface = HcSurface,
    onSurface = HcInk,
    surfaceVariant = HcSurfaceSunken,
    onSurfaceVariant = HcMuted,
    outline = HcBorder,
    error = HcDanger,
    onError = HcGround,
)

val LocalYourEyesColors = staticCompositionLocalOf { LightColors }

/** The user's "Cỡ chữ" choice, as a multiplier. See [TypeScale]. */
val LocalTypeScale = staticCompositionLocalOf { 1.0f }

/** Entry point for semantic colours: `AppTheme.colors.inkSecondary`. */
object AppTheme {
    val colors: YourEyesColors
        @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current

    val typeScale: Float
        @Composable @ReadOnlyComposable get() = LocalTypeScale.current
}

/**
 * Reads text size and contrast from [DisplaySettings], so changing either one on
 * the Hiển thị screen redraws the whole app on the next frame — the setting is
 * the demonstration of itself.
 */
@Composable
fun AppDemoTheme(content: @Composable () -> Unit) {
    val fontSizeOption by DisplaySettings.fontSizeOption.collectAsState()
    val highContrast by DisplaySettings.highContrast.collectAsState()

    val colors = if (highContrast) HighContrastColors else LightColors
    val scale = TypeScale.multiplierFor(fontSizeOption)

    CompositionLocalProvider(
        LocalYourEyesColors provides colors,
        LocalTypeScale provides scale,
    ) {
        MaterialTheme(
            colorScheme = if (highContrast) HighContrastColorScheme else LightColorScheme,
            typography = appTypography(scale),
            content = content,
        )
    }
}
