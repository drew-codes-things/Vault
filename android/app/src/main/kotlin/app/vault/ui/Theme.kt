package app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val VaultRed = Color(0xFFE5383B)
private val GradientTop = Color(0xFF1C0608)
private val GradientMiddle = Color(0xFF0D0B0C)
private val GradientBottom = Color(0xFF070708)
private val Glow = Color(0x61A0141C)

val VaultColors = darkColorScheme(
    primary = VaultRed,
    onPrimary = Color.White,
    secondary = Color(0xFFB82A2D),
    background = Color.Black,
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF140A0B),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF24100F),
    onSurfaceVariant = Color(0xFFD6C9C9),
    error = Color(0xFFFF6B6E),
)

@Composable
fun VaultBackground(content: @Composable () -> Unit) {
    val base = Brush.linearGradient(
        colors = listOf(GradientTop, GradientMiddle, GradientBottom),
        start = Offset.Zero,
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )
    val glow = Brush.radialGradient(
        colors = listOf(Glow, Color.Transparent),
        center = Offset.Zero,
        radius = 1400f,
    )
    Box(Modifier.fillMaxSize().background(base).background(glow)) {
        CompositionLocalProvider(LocalContentColor provides VaultColors.onBackground) { content() }
    }
}

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VaultColors) { VaultBackground(content) }
}
