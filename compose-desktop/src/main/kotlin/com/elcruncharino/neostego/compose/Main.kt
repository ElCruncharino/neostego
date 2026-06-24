/*
 * Compose Desktop entry point for NeoStego. Loads the :core plugins, then shows the app shell
 * (sidebar + content) wrapped in the brand theme, scaled to the desktop's detected display scale.
 * Theme mode (System/Light/Dark) is user-selectable in Settings and persisted.
 */
package com.elcruncharino.neostego.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elcruncharino.neostego.compose.engine.dataHidingAlgorithms
import com.elcruncharino.neostego.compose.engine.detectUiScale
import com.elcruncharino.neostego.compose.engine.loadThemeMode
import com.elcruncharino.neostego.compose.engine.saveThemeMode
import com.elcruncharino.neostego.compose.engine.watermarkingAlgorithms
import com.elcruncharino.neostego.compose.theme.NeoStegoTheme
import com.elcruncharino.neostego.compose.theme.ThemeMode
import com.elcruncharino.neostego.compose.ui.AppShell
import com.elcruncharino.neostego.compose.ui.Destination
import com.openstego.desktop.util.PluginManager

fun main() {
    PluginManager.loadPlugins()
    val dhAlgorithms = dataHidingAlgorithms()
    val wmAlgorithms = watermarkingAlgorithms()
    val uiScale = detectUiScale()
    application {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var dest by remember { mutableStateOf(Destination.HIDE) }
        val dark = when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        // Open maximized so the scaled content fits the user's actual screen (no fixed-size guess).
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "NeoStego — ${dest.title}",
            icon = painterResource("neostego.png"),
        ) {
            NeoStegoTheme(dark = dark) {
                // Drive Compose's density from the detected desktop scale so the UI matches native apps.
                val density = uiScale?.let { Density(it, 1f) } ?: LocalDensity.current
                CompositionLocalProvider(LocalDensity provides density) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppShell(
                            dhAlgorithms = dhAlgorithms,
                            wmAlgorithms = wmAlgorithms,
                            themeMode = themeMode,
                            onThemeChange = { themeMode = it; saveThemeMode(it) },
                            dest = dest,
                            onSelect = { dest = it },
                        )
                    }
                }
            }
        }
    }
}
