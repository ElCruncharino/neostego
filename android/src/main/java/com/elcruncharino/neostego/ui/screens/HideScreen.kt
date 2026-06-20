/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.theme.extractSeedColor
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.AlgorithmOption
import com.elcruncharino.neostego.ui.components.EmbedMapView
import com.elcruncharino.neostego.ui.components.FilePickCard
import com.elcruncharino.neostego.ui.components.OutputResultCard
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.SecurePasswordField
import com.elcruncharino.neostego.ui.components.ToggleRow
import com.elcruncharino.neostego.ui.components.readPasswordChars
import com.elcruncharino.neostego.ui.util.OutputResult
import com.elcruncharino.neostego.ui.util.computeEmbedMap
import com.elcruncharino.neostego.ui.util.displayName
import com.elcruncharino.neostego.ui.util.humanBytes
import com.elcruncharino.neostego.ui.util.imageDimensions
import com.elcruncharino.neostego.ui.util.mimeForName
import com.elcruncharino.neostego.ui.util.oversizeWarning
import com.elcruncharino.neostego.ui.util.readBytes
import com.elcruncharino.neostego.ui.util.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HideScreen(appState: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = appState.snackbar
    val s = appState.hide

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun setResult(r: OutputResult?) {
        s.result?.bytes?.fill(0)
        s.result = r
    }

    // Split output is written part-by-part through a chain of save dialogs; these track that chain.
    var splitParts by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var splitPartIndex by remember { mutableStateOf(-1) }

    val splitEligible = s.algorithm == StegoEngine.Algorithm.ADAPTIVE || s.algorithm == StegoEngine.Algorithm.MATCHING
    if (!splitEligible && s.splitMode) s.splitMode = false

    val options = StegoEngine.Options(s.jpegQuality, s.adaptiveCmd, s.adaptiveCmdMu, s.lsbBits, s.useCompression, s.useAes256)

    // Capacity estimate for the chosen cover/algorithm.
    LaunchedEffect(s.coverUri, s.algorithm, s.lsbBits, s.jpegQuality, s.splitMode) {
        val uri = s.coverUri
        s.capacity = if (uri == null || !StegoEngine.isImageAlgorithm(s.algorithm) || s.splitMode) {
            null
        } else {
            withContext(Dispatchers.IO) {
                imageDimensions(context, uri)?.let { (w, h) ->
                    runCatching { StegoEngine.capacityBytes(s.algorithm, w, h, options) }.getOrNull()
                }
            }
        }
    }

    // Cover-derived ("album-art") theming: extract a seed colour from the chosen image cover.
    LaunchedEffect(s.coverUri, s.algorithm) {
        val uri = s.coverUri
        appState.coverSeedArgb = if (uri == null || s.algorithm == StegoEngine.Algorithm.WAV) {
            null
        } else {
            runCatching { extractSeedColor(context, uri) }.getOrNull()
        }
    }

    // Image pickers: Photo Picker for images, document picker for audio/files.
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { s.coverUri = it ?: s.coverUri }
    val openCoverAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.coverUri = it ?: s.coverUri }
    val openMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.messageUri = it ?: s.messageUri }
    val pickCovers = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            s.splitCovers.clear()
            s.splitCovers.addAll(uris)
        }
    }

    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val r = s.result
        if (uri != null && r != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, r.bytes) }
                    snackbar.showSnackbar("Saved ${r.name}")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        }
    }

    fun shareResult() {
        val r = s.result ?: return
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    com.elcruncharino.neostego.ui.util.buildShareIntent(context, r.name, r.mime, r.bytes)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbar.showSnackbar("Unable to share: ${e.message ?: e}")
            }
        }
    }

    // --- Split output: one stego PNG per cover, written through a sequence of save dialogs. ---
    val saveSplitPart = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val parts = splitParts
        val idx = splitPartIndex
        if (uri != null && idx in parts.indices) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, parts[idx]) }
                    parts[idx].fill(0)
                    splitPartIndex = idx + 1
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        } else {
            parts.forEach { it.fill(0) }
            splitParts = emptyList()
            splitPartIndex = -1
        }
    }
    LaunchedEffect(splitPartIndex, splitParts.size) {
        val parts = splitParts
        val idx = splitPartIndex
        when {
            parts.isNotEmpty() && idx in parts.indices -> saveSplitPart.launch("stego_part${idx + 1}.png")
            parts.isNotEmpty() && idx >= parts.size -> {
                val count = parts.size
                splitParts = emptyList()
                splitPartIndex = -1
                snackbar.showSnackbar("Saved $count stego images")
            }
        }
    }

    fun runHideSplit() {
        val message = s.messageUri
        if (s.splitCovers.size < 2) {
            toast("Choose at least two cover images to split across")
            return
        }
        if (message == null) {
            toast("Choose a file to hide first")
            return
        }
        val pw = readPasswordChars(s.passwordView)
        s.busy = true
        scope.launch {
            try {
                val covers = s.splitCovers.toList()
                val parts = withContext(Dispatchers.IO) {
                    StegoEngine.embedSplit(
                        s.algorithm,
                        s.embedFileName,
                        readBytes(context, message),
                        displayName(context, message),
                        covers.map { readBytes(context, it) },
                        covers.map { displayName(context, it) },
                        pw,
                        options,
                    )
                }
                splitParts = parts
                splitPartIndex = 0 // triggers the LaunchedEffect to open the first save dialog
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                pw?.fill(' ')
                s.busy = false
            }
        }
    }

    fun runHide() {
        if (s.splitMode) {
            runHideSplit()
            return
        }
        val cover = s.coverUri
        val message = s.messageUri
        val coverKind = if (s.algorithm == StegoEngine.Algorithm.WAV) "audio file" else "image"
        if (cover == null) {
            toast("Choose a cover $coverKind first")
            return
        }
        if (message == null) {
            toast("Choose a file to hide first")
            return
        }
        if (StegoEngine.isImageAlgorithm(s.algorithm)) {
            oversizeWarning(context, cover)?.let {
                toast(it)
                return
            }
        }
        val pw = readPasswordChars(s.passwordView)
        s.busy = true
        scope.launch {
            try {
                val coverBytes = withContext(Dispatchers.IO) { readBytes(context, cover) }
                val stegoBytes = withContext(Dispatchers.IO) {
                    StegoEngine.embed(
                        s.algorithm,
                        s.embedFileName,
                        readBytes(context, message),
                        displayName(context, message),
                        coverBytes,
                        displayName(context, cover),
                        pw,
                        options,
                    )
                }
                val name = StegoEngine.outputName(s.algorithm)
                setResult(OutputResult(name, mimeForName(name), stegoBytes))

                // Truthful embed map: diff the cover against the produced stego (image covers only).
                if (StegoEngine.isImageAlgorithm(s.algorithm)) {
                    val blockBased = s.algorithm == StegoEngine.Algorithm.SI_UNIWARD ||
                        s.algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD ||
                        s.algorithm == StegoEngine.Algorithm.F5
                    s.embedMap = runCatching { computeEmbedMap(coverBytes, stegoBytes, blockBased) }.getOrNull()
                    s.embedThumb = withContext(Dispatchers.Default) { decodeThumbnail(coverBytes) }
                    s.resultStamp++
                } else {
                    s.embedMap = null
                    s.embedThumb = null
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                pw?.fill(' ')
                s.busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Hide a file inside a cover",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val isWav = s.algorithm == StegoEngine.Algorithm.WAV
        val needsJpegCover = s.algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD ||
            s.algorithm == StegoEngine.Algorithm.F5
        if (s.splitMode) {
            FilePickCard(
                label = "Cover images (split)",
                chosen = if (s.splitCovers.isEmpty()) null else "${s.splitCovers.size} images selected",
                hint = "Pick two or more images; the file is spread across them",
                onPick = { pickCovers.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        } else {
            FilePickCard(
                label = if (isWav) {
                    "Cover audio (WAV)"
                } else if (needsJpegCover) {
                    "Cover image (JPEG)"
                } else {
                    "Cover image"
                },
                chosen = s.coverUri?.let { displayName(context, it) },
                hint = when {
                    isWav -> "An uncompressed PCM WAV file"
                    needsJpegCover -> "An existing JPEG to hide the data in"
                    else -> "The image the data will be hidden in"
                },
                onPick = {
                    if (isWav) {
                        openCoverAudio.launch(arrayOf("audio/x-wav", "audio/wav", "audio/*"))
                    } else {
                        pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
            )
            s.capacity?.let {
                Text(
                    "Can hide up to about ${humanBytes(it)} in this image",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilePickCard(
            label = "File to hide",
            chosen = s.messageUri?.let { displayName(context, it) },
            hint = "Any file (document, photo, etc.)",
            onPick = { openMessage.launch(arrayOf("*/*")) },
        )

        SecurePasswordField(
            show = s.showPassword,
            onToggleShow = { s.showPassword = !s.showPassword },
            onViewCreated = { s.passwordView = it },
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                Text("Hiding method", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.SI_UNIWARD,
                    title = "SI-UNIWARD (JPEG)",
                    subtitle = "Side-informed JPEG steganography. Saves a JPEG and is the strongest choice at low embedding rates against modern detectors.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.SI_UNIWARD },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD,
                    title = "J-UNIWARD (JPEG cover)",
                    subtitle = "Hides directly in an existing JPEG. Faster and works without the original uncompressed image, but less stealthy than SI-UNIWARD.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.PLAIN_UNIWARD },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.F5,
                    title = "F5 (JPEG cover)",
                    subtitle = "Fast, classic JPEG steganography using matrix encoding. Good for small payloads in an existing JPEG.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.F5 },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.ADAPTIVE,
                    title = "Adaptive (PNG)",
                    subtitle = "HILL + STC: hides changes in textured areas to resist both statistical and AI steganalysis. Lossless PNG, lower capacity.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.ADAPTIVE },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.MATCHING,
                    title = "LSB matching (PNG)",
                    subtitle = "Higher capacity and faster; resists classical steganalysis. Lossless PNG.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.MATCHING },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.WAV,
                    title = "Audio (WAV)",
                    subtitle = "Hides data in the samples of an uncompressed PCM WAV file. Output is a WAV; pick an audio cover above.",
                    onClick = { s.algorithm = StegoEngine.Algorithm.WAV },
                )

                if (s.algorithm == StegoEngine.Algorithm.SI_UNIWARD) {
                    Spacer(Modifier.height(12.dp))
                    Text("JPEG quality: ${s.jpegQuality}", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = s.jpegQuality.toFloat(),
                        onValueChange = { s.jpegQuality = it.toInt() },
                        valueRange = 50f..100f,
                    )
                    Text(
                        "Higher quality keeps the image crisper but enlarges the file; 90 is a good default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (s.algorithm == StegoEngine.Algorithm.ADAPTIVE || s.algorithm == StegoEngine.Algorithm.MATCHING) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { s.showAdvanced = !s.showAdvanced }) {
                        Text(if (s.showAdvanced) "Advanced ▴" else "Advanced ▾")
                    }
                    if (s.showAdvanced) {
                        if (s.algorithm == StegoEngine.Algorithm.ADAPTIVE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Cluster changes (CMD)", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Synchronizes neighbouring edits for slightly better resistance. Leave on unless reproducing legacy output.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = s.adaptiveCmd, onCheckedChange = { s.adaptiveCmd = it })
                            }
                            if (s.adaptiveCmd) {
                                Spacer(Modifier.height(8.dp))
                                Text("Clustering strength (mu): ${"%.1f".format(s.adaptiveCmdMu)}")
                                Slider(
                                    value = s.adaptiveCmdMu.toFloat(),
                                    onValueChange = { s.adaptiveCmdMu = it.toDouble() },
                                    valueRange = 1f..9f,
                                    steps = 7,
                                )
                            }
                        } else { // LSB matching
                            Spacer(Modifier.height(8.dp))
                            Text("Bits per channel: ${s.lsbBits}")
                            Slider(
                                value = s.lsbBits.toFloat(),
                                onValueChange = { s.lsbBits = it.toInt() },
                                valueRange = 1f..8f,
                                steps = 6,
                            )
                            Text(
                                "More bits store more data but are easier to detect; 3 balances the two.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                ToggleRow(
                    title = "Compress payload",
                    subtitle = "GZIP the data before hiding. Usually shrinks it; turn off for already-compressed files.",
                    checked = s.useCompression,
                    onCheckedChange = { s.useCompression = it },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Use AES-256",
                    subtitle = "Stronger key size than the default AES-128. Only applies when a password is set.",
                    checked = s.useAes256,
                    onCheckedChange = { s.useAes256 = it },
                )
            }
        }
        if (splitEligible) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    ToggleRow(
                        title = "Split across covers",
                        subtitle = "Spread one file across several images (pick 2+ above). Each image holds one part; " +
                            "keep all of them to reveal.",
                        checked = s.splitMode,
                        onCheckedChange = { s.splitMode = it },
                    )
                }
            }
        }
        Card(shape = RoundedCornerShape(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Store original file name", fontWeight = FontWeight.SemiBold)
                    Text(
                        "The name is saved unencrypted. Leave off to keep it private; " +
                            "the file is revealed with a generic name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.embedFileName, onCheckedChange = { s.embedFileName = it })
            }
        }
        Text(
            when (s.algorithm) {
                StegoEngine.Algorithm.SI_UNIWARD, StegoEngine.Algorithm.PLAIN_UNIWARD, StegoEngine.Algorithm.F5 ->
                    "Share the saved JPEG as-is. Do not open and re-save it — re-compressing the JPEG destroys the hidden data."
                StegoEngine.Algorithm.WAV ->
                    "Keep the saved WAV as-is to share. Converting it to MP3/AAC or any lossy audio format destroys the hidden data."
                else ->
                    "Keep the saved PNG as-is to share. Re-saving or sending it as JPEG (or any other lossy format) destroys the hidden data."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrimaryActionButton(label = "Hide", busy = s.busy, onClick = { runHide() })

        s.result?.let { r ->
            OutputResultCard(
                name = r.name,
                onSave = { saveOutput.launch(r.name) },
                onShare = { shareResult() },
            )
            val map = s.embedMap
            if (map != null && map.changedCells > 0) {
                EmbedMapView(
                    coverImage = s.embedThumb,
                    active = map.active,
                    cols = map.cols,
                    rows = map.rows,
                    accentColor = MaterialTheme.colorScheme.primary,
                    replayKey = s.resultStamp,
                )
                val unit = if (map.blockBased) "blocks" else "cells"
                Text(
                    "Embedding touched ${map.changedCells} of ${map.totalCells} $unit " +
                        "(${"%.0f".format(map.fraction * 100)}%). Brightly lit $unit carry hidden data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Decodes a small thumbnail (~480px) from image bytes for the embed-map backdrop. */
private fun decodeThumbnail(bytes: ByteArray, target: Int = 480): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longer = maxOf(bounds.outWidth, bounds.outHeight)
    if (longer <= 0) return null
    var sample = 1
    while (longer / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}
