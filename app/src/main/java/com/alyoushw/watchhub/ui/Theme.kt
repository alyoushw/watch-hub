package com.alyoushw.watchhub.ui

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Warm cinema palette: near-black background with a golden-amber accent. */
val Gold = Color(0xFFE8A33D)

private val Colors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1C1206),
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFF2B861),
    onSecondary = Color(0xFF1C1206),
    secondaryContainer = Color(0xFF3A2A12),
    onSecondaryContainer = Color(0xFFFFE3B8),
    tertiary = Color(0xFFE5484D),
    background = Color(0xFF0E0D0B),
    onBackground = Color(0xFFF1EEE9),
    surface = Color(0xFF0E0D0B),
    onSurface = Color(0xFFF1EEE9),
    surfaceVariant = Color(0xFF26231F),
    onSurfaceVariant = Color(0xFFB9B2A8),
    surfaceContainer = Color(0xFF181614),
    surfaceContainerHigh = Color(0xFF201D1A),
    surfaceContainerHighest = Color(0xFF2A2622),
    outline = Color(0xFF4D4740),
    outlineVariant = Color(0xFF332E29),
)

/** Bright yellow used to show where the TV remote is. */
val FocusColor = Color(0xFFFFC94D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        // No colour wash on focus (it covered posters); focus is shown with a yellow outline instead.
        val ripple = RippleConfiguration(
            color = FocusColor,
            rippleAlpha = RippleAlpha(
                draggedAlpha = 0.16f,
                focusedAlpha = 0f,
                hoveredAlpha = 0.12f,
                pressedAlpha = 0.25f,
            ),
        )
        // The interface is Arabic, so keep it right-to-left whatever the phone language is.
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalRippleConfiguration provides ripple,
        ) {
            content()
        }
    }
}
