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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elcruncharino.neostego.compose.engine.WindowBounds
import com.elcruncharino.neostego.compose.engine.dataHidingAlgorithms
import com.elcruncharino.neostego.compose.engine.detectUiScale
import com.elcruncharino.neostego.compose.engine.loadThemeMode
import com.elcruncharino.neostego.compose.engine.loadWindowBounds
import com.elcruncharino.neostego.compose.engine.saveThemeMode
import com.elcruncharino.neostego.compose.engine.saveWindowBounds
import com.elcruncharino.neostego.compose.engine.watermarkingAlgorithms
import com.elcruncharino.neostego.compose.engine.windowBoundsOnScreen
import com.elcruncharino.neostego.compose.theme.NeoStegoTheme
import com.elcruncharino.neostego.compose.theme.ThemeMode
import com.elcruncharino.neostego.compose.ui.AppShell
import com.elcruncharino.neostego.compose.ui.Destination
import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoCmd
import com.openstego.desktop.OpenStegoException
import com.openstego.desktop.OpenStegoLauncher
import com.openstego.desktop.util.PluginManager
import com.openstego.desktop.util.UserPreferences

// Flags that launch the classic Swing UI — the accessible fallback (full AT-SPI/screen-reader support)
// for users whose setup the Compose UI doesn't serve well.
private val CLASSIC_FLAGS = setOf("--classic", "--accessible", "--swing")

/**
 * Single entry point for every way NeoStego is launched:
 *  - no arguments            → the modern Compose desktop UI (default)
 *  - --classic/--accessible  → the classic Swing UI (accessible fallback)
 *  - any other arguments     → the command-line interface (unchanged)
 * This lets one installed binary serve the GUI, the accessible GUI, and scripting.
 */
fun main(args: Array<String>) {
    when {
        args.isEmpty() -> {
            installNativeLauncherExitFix()
            launchComposeUi()
        }
        args.first() in CLASSIC_FLAGS -> {
            installNativeLauncherExitFix()
            OpenStegoLauncher.main(emptyArray()) // no args ⇒ Swing GUI
        }
        // CLI: run it ourselves so we can _exit with the real status code (skipping the teardown abort).
        else -> Runtime.getRuntime().halt(runCli(args))
    }
}

/**
 * jpackage's native launcher (libapplauncher.so) aborts with a "pure virtual method called" coredump
 * during its C++ static-destructor teardown (_dl_fini) on bleeding-edge toolchains (e.g. openSUSE
 * Tumbleweed / GCC 16). It happens after our app has already exited cleanly — harmless, but it dumps a
 * core and prints to stderr on every run. Calling _exit() (Runtime.halt) the instant the JVM begins
 * shutting down skips that native teardown entirely. GUI exits are always status 0.
 */
private fun installNativeLauncherExitFix() {
    Runtime.getRuntime().addShutdownHook(Thread { Runtime.getRuntime().halt(0) })
}

/** Run the command-line interface and return its exit code (mirrors OpenStegoLauncher's CLI setup). */
private fun runCli(args: Array<String>): Int = try {
    OpenStego.init()
    PluginManager.loadPlugins()
    UserPreferences.init()
    OpenStegoCmd.execute(args)
} catch (e: OpenStegoException) {
    System.err.println(e.message)
    1
} catch (e: Exception) {
    e.printStackTrace()
    1
}

private fun launchComposeUi() {
    PluginManager.loadPlugins()
    val dhAlgorithms = dataHidingAlgorithms()
    val wmAlgorithms = watermarkingAlgorithms()
    val uiScale = detectUiScale()
    // Size the window to a fraction of the PRIMARY display and let Compose center it there, so it
    // always opens on the user's main monitor (maximizing was landing it on a secondary output).
    val initialSize = run {
        val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        DpSize(
            (bounds.width * 0.66).toInt().coerceIn(900, 1500).dp,
            (bounds.height * 0.80).toInt().coerceIn(620, 1040).dp,
        )
    }
    application {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var dest by remember { mutableStateOf(Destination.HIDE) }
        val dark = when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        // Restore the last window geometry if we have a valid, still-on-screen one; otherwise center on
        // the primary screen at a size derived from that screen (first run / unplugged monitor).
        val saved = remember { loadWindowBounds()?.takeIf { windowBoundsOnScreen(it) } }
        val windowState = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.let { WindowPosition.Absolute(it.x.dp, it.y.dp) } ?: WindowPosition.Aligned(Alignment.Center),
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: initialSize,
        )
        // Track the last *floating* geometry in memory (so a maximized close still restores a sane size),
        // and persist once on close rather than writing prefs on every resize/move frame.
        val lastFloating = remember { mutableStateOf(saved?.copy(maximized = false)) }
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
                .collect { (placement, pos, size) ->
                    if (placement == WindowPlacement.Floating &&
                        pos is WindowPosition.Absolute &&
                        size.width.value > 0f &&
                        size.height.value > 0f
                    ) {
                        lastFloating.value = WindowBounds(
                            pos.x.value.toInt(),
                            pos.y.value.toInt(),
                            size.width.value.toInt(),
                            size.height.value.toInt(),
                            maximized = false,
                        )
                    }
                }
        }
        fun persistWindow() {
            val maximized = windowState.placement == WindowPlacement.Maximized
            lastFloating.value?.let { saveWindowBounds(it.copy(maximized = maximized)) }
        }
        Window(
            onCloseRequest = {
                persistWindow()
                exitApplication()
            },
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
                            onThemeChange = {
                                themeMode = it
                                saveThemeMode(it)
                            },
                            dest = dest,
                            onSelect = { dest = it },
                        )
                    }
                }
            }
        }
    }
}
