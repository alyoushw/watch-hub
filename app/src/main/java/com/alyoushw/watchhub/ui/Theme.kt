package com.alyoushw.watchhub.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The accent colours the user can pick in Settings (key, Arabic name, colour). Same list as the web app. */
val Accents = listOf(
    Triple("gold", "ذهبي", Color(0xFFE8A33D)),
    Triple("red", "أحمر", Color(0xFFE5484D)),
    Triple("blue", "أزرق", Color(0xFF3B8BEB)),
    Triple("green", "أخضر", Color(0xFF2FB36B)),
    Triple("purple", "بنفسجي", Color(0xFF9B6BEF)),
    Triple("pink", "وردي", Color(0xFFE5578F)),
)

fun accentColor(key: String): Color = Accents.firstOrNull { it.first == key }?.third ?: Accents.first().third

/**
 * The app's accent ("Gold" for history: it started as the only colour). Snapshot state, so every
 * screen that reads it redraws when the user picks another colour in Settings.
 */
var Gold: Color by mutableStateOf(Accents.first().third)
    private set

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF1C1206),
    primaryContainer = accent.copy(alpha = 0.25f).compositeOver(Color(0xFF0E0D0B)),
    onPrimaryContainer = Color(0xFFFFE9CC),
    secondary = accent.copy(alpha = 0.85f).compositeOver(Color.White),
    onSecondary = Color(0xFF1C1206),
    secondaryContainer = accent.copy(alpha = 0.25f).compositeOver(Color(0xFF0E0D0B)),
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

private fun lightScheme(accent: Color): androidx.compose.material3.ColorScheme {
    // A slightly deeper accent so it reads on a light background.
    val strong = accent.copy(alpha = 0.82f).compositeOver(Color.Black)
    return lightColorScheme(
        primary = strong,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color.White),
        onPrimaryContainer = Color(0xFF2A1A05),
        secondary = strong,
        onSecondary = Color.White,
        secondaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color.White),
        onSecondaryContainer = Color(0xFF2A1A05),
        tertiary = Color(0xFFC62F35),
        background = Color(0xFFFAF8F5),
        onBackground = Color(0xFF1C1A17),
        surface = Color(0xFFFAF8F5),
        onSurface = Color(0xFF1C1A17),
        surfaceVariant = Color(0xFFEDE7DF),
        onSurfaceVariant = Color(0xFF5F5850),
        surfaceContainer = Color(0xFFF3EFE9),
        surfaceContainerHigh = Color(0xFFECE6DE),
        surfaceContainerHighest = Color(0xFFE4DDD3),
        outline = Color(0xFFB9AFA2),
        outlineVariant = Color(0xFFDDD4C8),
    )
}

/** Bright yellow used to show where the TV remote is. */
val FocusColor = Color(0xFFFFC94D)

/**
 * The app's look: dark, light or following the phone (Settings › المظهر), with the chosen accent.
 * [forceDark] keeps the video player dark whatever the setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHubTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val settings = appContainer().settings
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val accentKey by settings.accent.collectAsStateWithLifecycle()
    val dark = forceDark || when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val accent = accentColor(accentKey)
    Gold = if (dark) accent else accent.copy(alpha = 0.82f).compositeOver(Color.Black)

    // Status and navigation bar icons dark on a light screen, light on a dark one.
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            val bg = if (dark) Color(0xFF0E0D0B) else Color(0xFFFAF8F5)
            w.decorView.setBackgroundColor(android.graphics.Color.argb(255, (bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt()))
        }
    }

    MaterialTheme(colorScheme = if (dark) darkScheme(accent) else lightScheme(accent)) {
        // No colour wash on focus (it covered posters); focus is shown with a yellow outline instead.
        val ripple = RippleConfiguration(
            color = if (dark) FocusColor else Gold,
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

/** True when the light look is in use (for the few hand-picked dark colours). */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f
