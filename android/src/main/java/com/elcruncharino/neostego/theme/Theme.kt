/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.elcruncharino.neostego.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6DF6),
    secondary = Color(0xFF3A6EA5),
    tertiary = Color(0xFF6750A4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    secondary = Color(0xFFAEC6E4),
    tertiary = Color(0xFFCFBCFF),
)

/** A background/foreground colour pair for a status indicator. */
data class StatusColors(val container: Color, val content: Color)

/** Watermark verdict severities, used to pick a contrast-safe colour pair. */
enum class VerdictLevel { PRESENT, WEAK, ABSENT }

/**
 * Colour pairs for the watermark verdict, hand-picked so the foreground meets WCAG AA contrast
 * (≥4.5:1, all pairs here are ≥5:1) against their own container in BOTH light and dark themes.
 *
 * The verdict must not depend on the Material You dynamic `surfaceVariant`, whose exact value is
 * derived from the user's wallpaper and therefore cannot be contrast-guaranteed. Rendering the
 * verdict on its own controlled container removes that dependency. Colour is paired with a distinct
 * icon at the call site so meaning never rests on colour alone (WCAG 1.4.1). Darkness is taken from
 * the *active* scheme (not the system), so the pairs stay correct when the theme is forced.
 */
@Composable
fun verdictColors(level: VerdictLevel): StatusColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (level) {
        VerdictLevel.PRESENT ->
            if (dark) {
                StatusColors(Color(0xFF2E4730), Color(0xFFA5D6A7))
            } else {
                StatusColors(Color(0xFFC8E6C9), Color(0xFF1B5E20))
            }
        VerdictLevel.WEAK ->
            if (dark) {
                StatusColors(Color(0xFF4A3411), Color(0xFFFFCC80))
            } else {
                StatusColors(Color(0xFFFFE0B2), Color(0xFF6E3B00))
            }
        VerdictLevel.ABSENT ->
            if (dark) {
                StatusColors(Color(0xFF4A2426), Color(0xFFF2B8B5))
            } else {
                StatusColors(Color(0xFFFFCDD2), Color(0xFF8C1D18))
            }
    }
}

/**
 * Application theme. The colour scheme is resolved by priority:
 *
 *  1. [seedColorArgb] — a colour the user picked in Settings;
 *  2. the OS Material You palette, when [useDynamicColor] is on and the device is Android 12+;
 *  3. a fixed brand fallback scheme.
 *
 * Light/dark follows [themeMode] (SYSTEM defers to [isSystemInDarkTheme]).
 *
 * Note: the Material 3 Expressive theme/components are still `internal` in the pinned `material3`
 * release, so the expressive *look* (floating toolbar, segmented groups, soft shapes, gradient
 * surfaces) is built from stable APIs plus custom composables rather than `MaterialExpressiveTheme`.
 */
@Composable
fun NeoStegoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = true,
    seedColorArgb: Int? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        seedColorArgb != null -> schemeFromSeed(Color(seedColorArgb), dark)
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
