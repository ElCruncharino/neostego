/*
 * Compose Desktop entry point for NeoStego. Loads the :core plugins, then shows the app shell
 * (sidebar + content) wrapped in the brand theme. This module is the in-progress replacement for the
 * Swing desktop UI; only the "Hide data" screen is fully ported so far.
 */
package com.elcruncharino.neostego.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.elcruncharino.neostego.compose.engine.dataHidingAlgorithms
import com.elcruncharino.neostego.compose.theme.NeoStegoTheme
import com.elcruncharino.neostego.compose.ui.AppShell
import com.openstego.desktop.util.PluginManager

fun main() {
    PluginManager.loadPlugins()
    val algorithms = dataHidingAlgorithms()
    application {
        var dark by remember { mutableStateOf(true) }
        Window(onCloseRequest = ::exitApplication, title = "NeoStego") {
            NeoStegoTheme(dark = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(algorithms = algorithms, dark = dark, onToggleDark = { dark = !dark })
                }
            }
        }
    }
}
