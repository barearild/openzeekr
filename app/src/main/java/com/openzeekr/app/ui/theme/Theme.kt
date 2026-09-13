package com.openzeekr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * OpenZeekr is a dark-committed "cockpit" design (see the redesign mockup): a cool
 * near-black ground, one electric-blue interactive accent, and semantic-only energy/
 * secured/alert colours. We force the dark scheme regardless of system setting.
 */
private val Bg          = Color(0xFF0C0F12)
private val Surface     = Color(0xFF14181C)
private val Surface2    = Color(0xFF1C2227)
private val Surface3    = Color(0xFF232B31)
private val Line        = Color(0xFF2A3238)
private val TextC       = Color(0xFFEAEEF1)
private val Muted       = Color(0xFF8B979E)
private val Faint       = Color(0xFF5E696F)
private val Accent      = Color(0xFF5AA9FF)   // electric blue — interactive
private val Energy      = Color(0xFFF5B740)   // amber — charging/energy
private val Neon        = Color(0xFF31E08A)   // charge progress
private val Good        = Color(0xFF46D8A0)   // secured/ok
private val Crit        = Color(0xFFFF6B6B)   // alert/open

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF07121F),
    primaryContainer = Color(0xFF16324B),
    onPrimaryContainer = Color(0xFFCFE4FF),
    secondary = Good,
    onSecondary = Color(0xFF00251A),
    tertiary = Energy,
    onTertiary = Color(0xFF2A1E00),
    background = Bg,
    onBackground = TextC,
    surface = Surface,
    onSurface = TextC,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Color(0xFF20272C),
    error = Crit,
    onError = Color(0xFF2A0A0A),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Extra brand tokens the screens use directly (beyond the Material scheme). */
object Brand {
    val accent = Accent
    val energy = Energy
    val neon = Neon
    val good = Good
    val crit = Crit
    val surface2 = Surface2
    val surface3 = Surface3
    val line = Line
    val muted = Muted
    val faint = Faint
    val badge = listOf(Color(0xFF6DB4FF), Color(0xFF3A6FF0))
    /** Back-compat alias (used by BrandBadge / ParkingScreen). */
    val gradient = badge

    /** Metallic paint "identity card" gradient for a given paint colour. */
    fun paintCard(paint: Color): Brush = Brush.radialGradient(
        colors = listOf(
            blend(paint, Color.White, 0.26f),
            paint,
            blend(paint, Color.Black, 0.46f),
        ),
    )
    private fun blend(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        1f,
    )
}

@Composable
fun OpenZeekrTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, content = content)
}
