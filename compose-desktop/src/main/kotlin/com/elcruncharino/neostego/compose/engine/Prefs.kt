/*
 * Lightweight persisted preferences (java.util.prefs) and app metadata for the Compose UI.
 */
package com.elcruncharino.neostego.compose.engine

import com.elcruncharino.neostego.compose.theme.ThemeMode
import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("com/elcruncharino/neostego")

fun loadThemeMode(): ThemeMode = runCatching { ThemeMode.valueOf(prefs.get("themeMode", ThemeMode.SYSTEM.name)) }.getOrDefault(ThemeMode.SYSTEM)

fun saveThemeMode(mode: ThemeMode) {
    runCatching { prefs.put("themeMode", mode.name) }
}

/** App version, read from the build-generated resource (falls back gracefully). */
fun appVersion(): String = runCatching {
    object {}.javaClass.getResourceAsStream("/neostego-version.txt")?.bufferedReader()?.use { it.readText().trim() }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
