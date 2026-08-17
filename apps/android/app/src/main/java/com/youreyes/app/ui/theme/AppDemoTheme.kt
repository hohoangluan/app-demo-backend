package com.youreyes.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = YourEyesCyan,
    onPrimary = YourEyesSurface,
    primaryContainer = YourEyesMintSoft,
    onPrimaryContainer = YourEyesNavy,
    secondary = YourEyesTeal,
    onSecondary = YourEyesSurface,
    background = YourEyesGradientStart,
    onBackground = YourEyesInk,
    surface = YourEyesSurface,
    onSurface = YourEyesInk,
    surfaceVariant = YourEyesMintSoft,
    onSurfaceVariant = YourEyesMuted,
    outline = YourEyesLine,
    error = YourEyesDanger,
    onError = YourEyesSurface,
)

@Composable
fun AppDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content,
    )
}
