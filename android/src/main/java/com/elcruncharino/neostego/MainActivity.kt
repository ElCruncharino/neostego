/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.elcruncharino.neostego.data.rememberThemePreferences
import com.elcruncharino.neostego.theme.NeoStegoTheme
import com.elcruncharino.neostego.ui.DEST_HIDE
import com.elcruncharino.neostego.ui.DEST_REVEAL
import com.elcruncharino.neostego.ui.StegoScaffold
import com.elcruncharino.neostego.ui.rememberAppState

private const val ACTION_HIDE = "com.elcruncharino.neostego.ACTION_HIDE"
private const val ACTION_REVEAL = "com.elcruncharino.neostego.ACTION_REVEAL"

/**
 * What the app was launched to do: which destination to open, plus any files handed in via a share
 * or shortcut so the right screen opens preselected.
 */
data class LaunchTarget(
    val destination: Int = DEST_HIDE,
    val coverUri: Uri? = null,
    val payloadUri: Uri? = null,
    val stegoUri: Uri? = null,
    val splitCoverUris: List<Uri> = emptyList(),
    val wavCover: Boolean = false,
)

class MainActivity : ComponentActivity() {
    // Drives the initial screen + preselection; updated when a new share/shortcut arrives.
    private val launchTarget = mutableStateOf(LaunchTarget())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Prevent screenshots, screen recording and Recents thumbnails from capturing secrets/passwords
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        launchTarget.value = parseLaunch(intent)
        setContent {
            val prefs = rememberThemePreferences()
            val snackbar = remember { SnackbarHostState() }
            val appState = rememberAppState(prefs, snackbar)
            NeoStegoTheme(
                themeMode = prefs.themeMode,
                useDynamicColor = prefs.useDynamicColor,
                seedColorArgb = prefs.seedColorArgb,
                // Cover-derived ("album-art") seed only applies while dynamic colour is enabled.
                coverSeedArgb = if (prefs.useDynamicColor) appState.coverSeedArgb else null,
            ) {
                StegoScaffold(launchTarget.value, appState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTarget.value = parseLaunch(intent)
    }

    /**
     * Resolves an incoming intent (launcher, app shortcut, or a share from another app via one of the
     * labeled share-target aliases) into a [LaunchTarget].
     */
    private fun parseLaunch(intent: Intent?): LaunchTarget {
        if (intent == null) return LaunchTarget()
        when (intent.action) {
            ACTION_HIDE -> return LaunchTarget(destination = DEST_HIDE)
            ACTION_REVEAL -> return LaunchTarget(destination = DEST_REVEAL)
        }
        // Which labeled share target was used (set via the activity-alias the chooser launched).
        val isReveal = intent.component?.className?.endsWith("ShareReveal") == true
        val type = intent.type ?: ""
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.parcelableExtra(Intent.EXTRA_STREAM)
                when {
                    uri == null -> LaunchTarget()
                    isReveal -> LaunchTarget(destination = DEST_REVEAL, stegoUri = uri)
                    type.startsWith("image/") -> LaunchTarget(destination = DEST_HIDE, coverUri = uri)
                    type.startsWith("audio/") -> LaunchTarget(destination = DEST_HIDE, coverUri = uri, wavCover = true)
                    else -> LaunchTarget(destination = DEST_HIDE, payloadUri = uri) // any other file → payload
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
                when {
                    uris.isEmpty() -> LaunchTarget()
                    isReveal -> LaunchTarget(destination = DEST_REVEAL, stegoUri = uris.first())
                    else -> LaunchTarget(destination = DEST_HIDE, splitCoverUris = uris) // many covers → split
                }
            }
            else -> LaunchTarget()
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name) as? T
    }

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java)
    } else {
        getParcelableArrayListExtra(name)
    }
