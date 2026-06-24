/*
 * Lightweight persisted preferences (java.util.prefs) and app metadata for the Compose UI.
 */
package com.elcruncharino.neostego.compose.engine

import com.elcruncharino.neostego.compose.theme.ThemeMode
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("com/elcruncharino/neostego")

fun loadThemeMode(): ThemeMode = runCatching { ThemeMode.valueOf(prefs.get("themeMode", ThemeMode.SYSTEM.name)) }.getOrDefault(ThemeMode.SYSTEM)

fun saveThemeMode(mode: ThemeMode) {
    // flush() is required because our launcher _exit()s on shutdown (to dodge jpackage's native
    // teardown abort), which skips java.util.prefs' normal flush-on-exit.
    runCatching {
        prefs.put("themeMode", mode.name)
        prefs.flush()
    }
}

/** Saved window geometry. Coordinates/size are in the window's logical units (dp at platform density). */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/** The last saved window geometry, or null if none/invalid (first run). */
fun loadWindowBounds(): WindowBounds? = runCatching {
    val w = prefs.getInt("win.w", -1)
    val h = prefs.getInt("win.h", -1)
    if (w <= 0 || h <= 0) return null
    WindowBounds(
        prefs.getInt("win.x", 0),
        prefs.getInt("win.y", 0),
        w,
        h,
        prefs.getBoolean("win.max", false),
    )
}.getOrNull()

fun saveWindowBounds(b: WindowBounds) {
    runCatching {
        prefs.putInt("win.x", b.x)
        prefs.putInt("win.y", b.y)
        prefs.putInt("win.w", b.width)
        prefs.putInt("win.h", b.height)
        prefs.putBoolean("win.max", b.maximized)
        prefs.flush() // see saveThemeMode: explicit flush because the launcher _exit()s on shutdown
    }
}

/**
 * True if a window at [bounds] would be visible on some currently-connected display. Guards against
 * restoring onto a monitor that has since been unplugged (which would open the window off-screen).
 */
fun windowBoundsOnScreen(bounds: WindowBounds): Boolean = runCatching {
    val rect = java.awt.Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        device.configurations.any { it.bounds.intersects(rect) }
    }
}.getOrDefault(false)

/** App version, read from the build-generated resource (falls back gracefully). */
fun appVersion(): String = runCatching {
    object {}.javaClass.getResourceAsStream("/neostego-version.txt")?.bufferedReader()?.use { it.readText().trim() }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
