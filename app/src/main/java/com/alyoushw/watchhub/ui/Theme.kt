package com.alyoushw.watchhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val Colors = darkColorScheme(
    primary = Color(0xFFE5484D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1A1D),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFF5A524),
    onSecondary = Color(0xFF1A1200),
    secondaryContainer = Color(0xFF3D2C0A),
    onSecondaryContainer = Color(0xFFFFE3B0),
    background = Color(0xFF101014),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF24242D),
    onSurfaceVariant = Color(0xFFB4B4C0),
    surfaceContainer = Color(0xFF1A1A21),
    surfaceContainerHigh = Color(0xFF22222A),
    surfaceContainerHighest = Color(0xFF2A2A33),
    outline = Color(0xFF4A4A57),
    outlineVariant = Color(0xFF32323C),
)

@Composable
fun WatchHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        // The interface is Arabic, so keep it right-to-left whatever the phone language is.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
