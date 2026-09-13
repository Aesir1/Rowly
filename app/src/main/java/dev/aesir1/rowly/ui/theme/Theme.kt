package dev.aesir1.rowly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A cold, high-contrast palette. Dynamic colour is deliberately not used: this screen is read at
// arm's length, in sunlight, while moving, and it needs predictable contrast rather than whatever
// the wallpaper happens to be.
private val Deep = Color(0xFF0B2A3A)
private val Water = Color(0xFF1B6C8C)
private val Foam = Color(0xFF5FD3D8)
private val Blade = Color(0xFFE8B84B)

private val DarkColors = darkColorScheme(
    primary = Foam,
    onPrimary = Color(0xFF05202C),
    secondary = Blade,
    background = Color(0xFF061620),
    surface = Color(0xFF0D2432),
    surfaceVariant = Color(0xFF14344A),
    // Set explicitly, or the navigation bar falls back to the Material baseline purple.
    surfaceContainer = Color(0xFF102B3C),
    secondaryContainer = Color(0xFF17485E),
    onSecondaryContainer = Foam,
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Water,
    onPrimary = Color.White,
    secondary = Blade,
    background = Color(0xFFF3F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFDCE7EC),
    surfaceContainer = Color(0xFFE4EDF1),
    secondaryContainer = Color(0xFFC6DCE4),
    onSecondaryContainer = Deep,
    onSurface = Deep,
    onSurfaceVariant = Color(0xFF41606E),
    error = Color(0xFFB3261E),
)

/**
 * Metric numbers get their own styles. They are the reason the screen exists, so they are sized to
 * be readable from the far end of a boat rather than to sit politely in the type scale.
 */
object RowlyText {
    val Hero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 112.sp,
        lineHeight = 112.sp,
    )
    val Metric = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
    )
}

@Composable
fun RowlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
