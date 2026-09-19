package com.inteldes.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val IdLightColorScheme = lightColorScheme(
    primary = IdColor.Accent,
    onPrimary = IdColor.White,
    primaryContainer = IdColor.AccentSurface100,
    onPrimaryContainer = IdColor.Accent700,
    secondary = IdColor.Neutral900,
    onSecondary = IdColor.Bg,
    background = IdColor.Bg,
    onBackground = IdColor.Text,
    surface = IdColor.Bg,
    onSurface = IdColor.Text,
    surfaceVariant = IdColor.Neutral100,
    onSurfaceVariant = IdColor.Neutral700,
    outline = IdColor.Divider,
    outlineVariant = IdColor.Neutral300,
    error = IdColor.Accent,
    onError = IdColor.White,
)

// The prototype is not designed with a dark palette; reuse the light one so the
// app stays legible if the OS forces dark mode, rather than shipping something unstyled.
private val IdDarkColorScheme = IdLightColorScheme

@Composable
fun IntelDesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) IdDarkColorScheme else IdLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = IdShapes,
        typography = IdTypography,
        content = content,
    )
}
