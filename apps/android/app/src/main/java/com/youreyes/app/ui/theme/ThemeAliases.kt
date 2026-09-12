package com.youreyes.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Composable-scoped colour roles.
 *
 * `AppTheme.colors.ink` is the form to reach for in new code. These aliases exist
 * because the fourteen existing screens name raw tokens (`YourEyesInk`,
 * `YourEyesMuted`, ...) roughly 250 times, and a raw token cannot follow the
 * high-contrast swap — it is a top-level `val` and knows nothing about the theme.
 * Renaming each token to the matching alias below is a change that can be read
 * and reviewed; restructuring 250 call sites to hold a local
 * `val colors = AppTheme.colors` is not.
 *
 * Two mappings are not one-to-one and are worth knowing:
 *
 *  - `YourEyesCyan` and `YourEyesTeal` were used for text and for fills alike.
 *    The bright versions fail AA as text on white, so lettering resolves through
 *    [accentTextColor] and only fills use [accentColor].
 *  - `YourEyesNavy` collapses into [inkColor]. Two near-black text colours never
 *    encoded anything; they were two shades that happened to exist.
 */

val inkColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.ink

val mutedColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.inkSecondary

val disabledColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.inkDisabled

val surfaceColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.surface

val surfaceSunkenColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.surfaceSunken

val borderColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.border

val lineColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.line

/** Fills and large icons only. */
val accentColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.accent

/** Anything that carries lettering. */
val accentTextColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.accentText

val accentSoftColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.accentSoft

val successColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.success

val successSoftColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.successSoft

val warningColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.warning

val warningSoftColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.warningSoft

val dangerColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.danger

val dangerSoftColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.dangerSoft

val onAccentColor: Color
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.onAccent

/**
 * Shadow tint. High contrast returns a fully transparent colour rather than a
 * darker one: a drop shadow reads as nothing against a near-black ground, and
 * every component that casts one draws a hard border in that mode instead.
 */
val shadowColor: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalYourEyesColors.current.isHighContrast) Color.Transparent else YourEyesShadow

/** True while the high-contrast palette is in force. */
val isHighContrastMode: Boolean
    @Composable @ReadOnlyComposable get() = LocalYourEyesColors.current.isHighContrast
