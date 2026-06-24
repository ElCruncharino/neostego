/*
 * Compose Desktop entry point for NeoStego. Loads the :core plugins, then shows the app shell
 * (sidebar + content) wrapped in the brand theme, scaled to the desktop's detected display scale.
 * This module is the in-progress replacement for the Swing desktop UI; only the "Hide data" screen
 * is fully ported so far.
 */
package com.elcruncharino.neostego.compose

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
import com.elcruncharino.neostego.compose.engine.watermarkingAlgorithms
import com.elcruncharino.neostego.compose.theme.NeoStegoTheme
import com.elcruncharino.neostego.compose.ui.AppShell
import com.openstego.desktop.util.PluginManager

fun main() {
    PluginManager.loadPlugins()
    val dhAlgorithms = dataHidingAlgorithms()
    val wmAlgorithms = watermarkingAlgorithms()
    val uiScale = detectUiScale()
    application {
        var dark by remember { mutableStateOf(true) }
        // Open maximized so the scaled content fits the user's actual screen (no fixed-size guess).
        val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "NeoStego",
            icon = painterResource("neostego.png"),
        ) {
            NeoStegoTheme(dark = dark) {
                // Drive Compose's density from the detected desktop scale so the UI matches native
                // apps. Falls back to the platform density when no scale signal is available.
                val density = uiScale?.let { Density(it, 1f) } ?: LocalDensity.current
                CompositionLocalProvider(LocalDensity provides density) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppShell(
                            dhAlgorithms = dhAlgorithms,
                            wmAlgorithms = wmAlgorithms,
                            dark = dark,
                            onToggleDark = { dark = !dark },
                        )
                    }
                }
            }
        }
    }
}
