package com.couchmode.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Placeholder default color schemes. Revisit once there's an actual app icon
// / brand palette to build around — not a priority before Phase 4 (UI).
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun CouchModeTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
