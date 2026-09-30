package dev.aesir1.rowly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.aesir1.rowly.training.PhaseType
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
    // The FAB and tonal buttons draw from here; unset it falls back to baseline purple.
    primaryContainer = Color(0xFF0F4258),
    onPrimaryContainer = Foam,
    secondary = Blade,
    background = Color(0xFF061620),
    surface = Color(0xFF0D2432),
    surfaceVariant = Color(0xFF14344A),
    // The whole container ladder is set explicitly, or cards, menus and dialogs fall back to
    // the Material baseline purple instead of the app's cold blue-gray.
    surfaceContainerLowest = Color(0xFF0B2130),
    surfaceContainerLow = Color(0xFF0F2735),
    surfaceContainer = Color(0xFF102B3C),
    surfaceContainerHigh = Color(0xFF14344A),
    surfaceContainerHighest = Color(0xFF183E52),
    secondaryContainer = Color(0xFF17485E),
    onSecondaryContainer = Foam,
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Water,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E4EF),
    onPrimaryContainer = Deep,
    secondary = Blade,
    background = Color(0xFFF3F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFDCE7EC),
    // Same reason as the dark scheme: every container level stays in the blue-gray family.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFE9F0F4),
    surfaceContainer = Color(0xFFE4EDF1),
    surfaceContainerHigh = Color(0xFFDCE7EC),
    surfaceContainerHighest = Color(0xFFD3E1E8),
    secondaryContainer = Color(0xFFC6DCE4),
    onSecondaryContainer = Deep,
    onSurface = Deep,
    onSurfaceVariant = Color(0xFF41606E),
    error = Color(0xFFB3261E),
)

/**
 * The one place training phase colors are defined: recovery is green, strength is orange, speed
 * is red, everywhere in the app. [container] fills large surfaces (the recording screen while a
 * training runs) with [on] guaranteed readable on top; [accent] is the same hue saturated for
 * small marks like the preview bars, which drowned in the pale container tints on a card.
 */
@Immutable
data class PhaseColor(val container: Color, val on: Color, val accent: Color)

private val LightPhaseColors = mapOf(
    PhaseType.RECOVERY to
        PhaseColor(Color(0xFFCDE8CB), Color(0xFF10321A), Color(0xFF4C9B57)),
    PhaseType.STRENGTH to
        PhaseColor(Color(0xFFFFDDB0), Color(0xFF4A2B00), Color(0xFFE8952E)),
    PhaseType.SPEED to
        PhaseColor(Color(0xFFFFCDD2), Color(0xFF5C1016), Color(0xFFD9534F)),
)

private val DarkPhaseColors = mapOf(
    PhaseType.RECOVERY to
        PhaseColor(Color(0xFF17421C), Color(0xFFBFE8C0), Color(0xFF66BB6A)),
    PhaseType.STRENGTH to
        PhaseColor(Color(0xFF4A2F00), Color(0xFFFFD9A0), Color(0xFFF0A94B)),
    PhaseType.SPEED to
        PhaseColor(Color(0xFF4A1216), Color(0xFFFFB4B0), Color(0xFFE57373)),
)

@Composable
fun PhaseType.phaseColor(): PhaseColor =
    (if (isSystemInDarkTheme()) DarkPhaseColors else LightPhaseColors).getValue(this)

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
