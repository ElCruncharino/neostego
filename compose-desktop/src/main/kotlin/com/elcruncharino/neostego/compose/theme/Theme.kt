/*
 * Desktop theme for the Compose NeoStego UI. Mirrors the Android app's brand palette
 * (android/.../theme/Theme.kt) so desktop and mobile read as one product. Desktop has no Material You
 * dynamic colour, so this resolves to a fixed light/dark brand scheme driven by the `dark` flag.
 */
package com.elcruncharino.neostego.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.elcruncharino.neostego.compose.engine.VerdictLevel

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

@Composable
fun NeoStegoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** A contrast-safe container/content colour pair for a status indicator. */
data class StatusColors(val container: Color, val content: Color)

/**
 * Hand-picked container/content pairs for the watermark verdict, each meeting WCAG AA contrast
 * (>=4.5:1) in BOTH light and dark themes — ported from the Android app rather than relying on the
 * Material container roles (whose contrast isn't guaranteed). Paired with a distinct icon at the call
 * site so meaning never rests on colour alone (WCAG 1.4.1).
 */
@Composable
fun verdictColors(level: VerdictLevel): StatusColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (level) {
        VerdictLevel.PRESENT ->
            if (dark) StatusColors(Color(0xFF2E4730), Color(0xFFA5D6A7)) else StatusColors(Color(0xFFC8E6C9), Color(0xFF1B5E20))
        VerdictLevel.WEAK ->
            if (dark) StatusColors(Color(0xFF4A3411), Color(0xFFFFCC80)) else StatusColors(Color(0xFFFFE0B2), Color(0xFF6E3B00))
        VerdictLevel.ABSENT ->
            if (dark) StatusColors(Color(0xFF4A2426), Color(0xFFF2B8B5)) else StatusColors(Color(0xFFFFCDD2), Color(0xFF8C1D18))
    }
}
