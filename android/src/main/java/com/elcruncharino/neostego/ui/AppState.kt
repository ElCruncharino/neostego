/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui

import android.net.Uri
import android.widget.EditText
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.data.ThemePreferences
import com.elcruncharino.neostego.ui.util.EmbedMap
import com.elcruncharino.neostego.ui.util.OutputResult

/** Top-level destinations. */
const val DEST_HIDE = 0
const val DEST_REVEAL = 1
const val DEST_WATERMARK = 2
const val DEST_SETTINGS = 3

/** Hide-screen inputs and produced output, hoisted so they survive switching between tabs. */
@Stable
class HideState {
    var coverUri by mutableStateOf<Uri?>(null)
    var messageUri by mutableStateOf<Uri?>(null)
    var showPassword by mutableStateOf(false)
    var passwordView by mutableStateOf<EditText?>(null)
    var algorithm by mutableStateOf(StegoEngine.Algorithm.ADAPTIVE)
    var embedFileName by mutableStateOf(false)
    var jpegQuality by mutableStateOf(90)
    var adaptiveCmd by mutableStateOf(true)
    var adaptiveCmdMu by mutableStateOf(3.0)
    var lsbBits by mutableStateOf(3)
    var showAdvanced by mutableStateOf(false)
    var useCompression by mutableStateOf(true)
    var useAes256 by mutableStateOf(false)
    var splitMode by mutableStateOf(false)
    val splitCovers = mutableStateListOf<Uri>()
    var busy by mutableStateOf(false)
    var capacity by mutableStateOf<Int?>(null)
    var result by mutableStateOf<OutputResult?>(null)

    // Embed-map animation: the changed-cell map, a thumbnail of the cover, and a replay counter.
    var embedMap by mutableStateOf<EmbedMap?>(null)
    var embedThumb by mutableStateOf<ImageBitmap?>(null)
    var resultStamp by mutableStateOf(0)
}

/** Reveal-screen input and produced output. */
@Stable
class RevealState {
    var stegoUri by mutableStateOf<Uri?>(null)
    var showPassword by mutableStateOf(false)
    var passwordView by mutableStateOf<EditText?>(null)
    var busy by mutableStateOf(false)
    var result by mutableStateOf<OutputResult?>(null)
    var revealStamp by mutableStateOf(0)
}

/** Watermark-screen state (generate / embed / verify). */
@Stable
class WatermarkState {
    var mode by mutableStateOf(0) // 0 = generate, 1 = embed, 2 = verify
    var algo by mutableStateOf(StegoEngine.WmAlgorithm.DWT_SVD)
    var busy by mutableStateOf(false)
    var sigUri by mutableStateOf<Uri?>(null)
    var coverUri by mutableStateOf<Uri?>(null)
    var markedUri by mutableStateOf<Uri?>(null)
    var outputJpeg by mutableStateOf(false)
    var jpegQuality by mutableStateOf(90)
    var verdict by mutableStateOf<StegoEngine.WmVerdict?>(null)
    var showPassword by mutableStateOf(false)
    var passwordView by mutableStateOf<EditText?>(null)
    var result by mutableStateOf<OutputResult?>(null)
}

/** Holds navigation, the cover-derived theme seed, and the per-screen state holders. */
@Stable
class AppState(
    val themePrefs: ThemePreferences,
    val snackbar: SnackbarHostState,
) {
    var dest by mutableStateOf(DEST_HIDE)

    /** Seed colour derived from the current Hide cover image; drives "album-art" theming. */
    var coverSeedArgb by mutableStateOf<Int?>(null)

    val hide = HideState()
    val reveal = RevealState()
    val watermark = WatermarkState()
}

@Composable
fun rememberAppState(themePrefs: ThemePreferences, snackbar: SnackbarHostState): AppState =
    remember(themePrefs, snackbar) { AppState(themePrefs, snackbar) }
