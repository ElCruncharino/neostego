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
