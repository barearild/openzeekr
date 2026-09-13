package com.openzeekr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// EV teal -> blue brand ramp (matches the app icon).
private val Teal = Color(0xFF14C8BE)
private val TealDim = Color(0xFF0E8F88)
private val Blue = Color(0xFF3B82F6)
private val BoltYellow = Color(0xFFEFFF3A)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201E),
    primaryContainer = Color(0xFF0E4A46),
    onPrimaryContainer = Color(0xFF9FF3EC),
    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF0A2447),
    secondaryContainer = Color(0xFF1E3A63),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = BoltYellow,
    onTertiary = Color(0xFF2B2E00),
    background = Color(0xFF0D1013),
    onBackground = Color(0xFFE4E7EA),
    surface = Color(0xFF14181D),
    onSurface = Color(0xFFE4E7EA),
    surfaceVariant = Color(0xFF232A31),
    onSurfaceVariant = Color(0xFFB9C2CC),
    outline = Color(0xFF3A424B),
    error = Color(0xFFFF7A6E),
)

private val LightColors = lightColorScheme(
    primary = TealDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2EC),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF2E5AAC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E4FF),
    onSecondaryContainer = Color(0xFF001A41),
    tertiary = Color(0xFF6B7000),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFE6EBF0),
    onSurfaceVariant = Color(0xFF444B52),
    outline = Color(0xFFC2CAD2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Brand accents reusable by screens (gradients, bolt color).
object Brand {
    val teal = Teal
    val blue = Blue
    val bolt = BoltYellow
    val gradient = listOf(Teal, Blue)
}

@Composable
fun OpenZeekrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
