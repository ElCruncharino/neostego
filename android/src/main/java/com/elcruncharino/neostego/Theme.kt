/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
 * icon at the call site so meaning never rests on colour alone (WCAG 1.4.1).
 */
@Composable
fun verdictColors(level: VerdictLevel): StatusColors {
    val dark = isSystemInDarkTheme()
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
 * Application theme: uses Material You dynamic colors on Android 12+, falling back to a fixed
 * scheme on older devices, and follows the system light/dark setting.
 */
@Composable
fun NeoStegoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
