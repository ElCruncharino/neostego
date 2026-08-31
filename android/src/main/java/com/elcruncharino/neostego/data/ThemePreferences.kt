/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Which light/dark scheme to use, independent of the system setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Persisted theme settings, backed by a small [android.content.SharedPreferences] file. Each setter
 * writes through to disk synchronously-enough for a settings screen and updates an in-memory Compose
 * state so the UI recomposes immediately.
 *
 * - [themeMode]      light / dark / follow-system
 * - [useDynamicColor] opt into the OS Material You palette (Android 12+) when no seed colour applies
 * - [seedColorArgb]   a user-chosen seed colour, or null to fall back to dynamic / the fixed default
 */
class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM),
    )
        private set

    var useDynamicColor by mutableStateOf(prefs.getBoolean(KEY_DYNAMIC, true))
        private set

    var seedColorArgb by mutableStateOf(
        if (prefs.contains(KEY_SEED)) prefs.getInt(KEY_SEED, 0) else null,
    )
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun updateDynamicColor(enabled: Boolean) {
        useDynamicColor = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun updateSeedColor(argb: Int?) {
        seedColorArgb = argb
        prefs.edit().apply {
            if (argb == null) remove(KEY_SEED) else putInt(KEY_SEED, argb)
        }.apply()
    }

    companion object {
        private const val PREFS = "neostego_theme"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_DYNAMIC = "use_dynamic_color"
        private const val KEY_SEED = "seed_color_argb"
    }
}

/** Remembers a single [ThemePreferences] instance for the lifetime of the composition. */
@Composable
fun rememberThemePreferences(): ThemePreferences {
    val context = LocalContext.current
    return remember { ThemePreferences(context) }
}
