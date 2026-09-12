package com.openzeekr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    secondary = Color(0xFF9CCC65),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    secondary = Color(0xFF4C662B),
)

@Composable
fun OpenZeekrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
