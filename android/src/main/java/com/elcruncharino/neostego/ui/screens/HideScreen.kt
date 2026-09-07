/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.R
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.AlgorithmOption
import com.elcruncharino.neostego.ui.components.FilePickCard
import com.elcruncharino.neostego.ui.components.OutputResultCard
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.SecurePasswordField
import com.elcruncharino.neostego.ui.components.ToggleRow
import com.elcruncharino.neostego.ui.components.readPasswordChars
import com.elcruncharino.neostego.ui.util.OutputResult
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

    // Resource strings looked up once here (composition-aware) and reused, with runtime values applied via
    // String.format, in callbacks that run outside composition (launcher callbacks, coroutines).
    val toastSavedFileTemplate = stringResource(R.string.toast_saved_file)
    val errorSavingTemplate = stringResource(R.string.error_saving)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val errorSharingTemplate = stringResource(R.string.error_sharing)
    val toastSavedStegoImagesTemplate = stringResource(R.string.toast_saved_stego_images)
    val errorNeedTwoCovers = stringResource(R.string.error_need_two_covers)
    val errorChooseFileToHide = stringResource(R.string.error_choose_file_to_hide)
    val errorFailedToHide = stringResource(R.string.error_failed_to_hide)
    val coverKindAudioFile = stringResource(R.string.cover_kind_audio_file)
    val coverKindImage = stringResource(R.string.cover_kind_image)
    val errorChooseCoverTemplate = stringResource(R.string.error_choose_cover)

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
                    snackbar.showSnackbar(String.format(toastSavedFileTemplate, r.name))
                } catch (e: Exception) {
                    snackbar.showSnackbar(String.format(errorSavingTemplate, e.message ?: e))
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
                context.startActivity(android.content.Intent.createChooser(intent, shareChooserTitle))
            } catch (e: Exception) {
                snackbar.showSnackbar(String.format(errorSharingTemplate, e.message ?: e))
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
                    snackbar.showSnackbar(String.format(errorSavingTemplate, e.message ?: e))
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
                snackbar.showSnackbar(String.format(toastSavedStegoImagesTemplate, count))
            }
        }
    }

    fun runHideSplit() {
        val message = s.messageUri
        if (s.splitCovers.size < 2) {
            toast(errorNeedTwoCovers)
            return
        }
        if (message == null) {
            toast(errorChooseFileToHide)
            return
        }
        val pw = readPasswordChars(s.passwordView)
        s.busy = true
        s.progress = null // split runs across covers; show an indeterminate bar
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
                snackbar.showSnackbar(e.message ?: errorFailedToHide)
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
        val coverKind = if (s.algorithm == StegoEngine.Algorithm.WAV) coverKindAudioFile else coverKindImage
        if (cover == null) {
            toast(String.format(errorChooseCoverTemplate, coverKind))
            return
        }
        if (message == null) {
            toast(errorChooseFileToHide)
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
        s.progress = null
        s.startedAtMs = System.currentTimeMillis()
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
                        onProgress = { f -> s.progress = f },
                    )
                }
                val name = StegoEngine.outputName(s.algorithm)
                setResult(OutputResult(name, mimeForName(name), stegoBytes))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: errorFailedToHide)
            } finally {
                pw?.fill(' ')
                s.busy = false
                s.progress = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.hide_screen_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val isWav = s.algorithm == StegoEngine.Algorithm.WAV
        val needsJpegCover = s.algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD ||
            s.algorithm == StegoEngine.Algorithm.F5
        if (s.splitMode) {
            FilePickCard(
                label = stringResource(R.string.label_cover_images_split),
                chosen = if (s.splitCovers.isEmpty()) null else stringResource(R.string.split_images_selected, s.splitCovers.size),
                hint = stringResource(R.string.hint_split_covers),
                onPick = { pickCovers.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        } else {
            FilePickCard(
                label = if (isWav) {
                    stringResource(R.string.label_cover_audio_wav)
                } else if (needsJpegCover) {
                    stringResource(R.string.label_cover_image_jpeg)
                } else {
                    stringResource(R.string.label_cover_image)
                },
                chosen = s.coverUri?.let { displayName(context, it) },
                hint = when {
                    isWav -> stringResource(R.string.hint_cover_wav)
                    needsJpegCover -> stringResource(R.string.hint_cover_jpeg)
                    else -> stringResource(R.string.hint_cover_image)
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
                    stringResource(R.string.capacity_estimate, humanBytes(context, it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilePickCard(
            label = stringResource(R.string.label_file_to_hide),
            chosen = s.messageUri?.let { displayName(context, it) },
            hint = stringResource(R.string.hint_file_to_hide),
            onPick = { openMessage.launch(arrayOf("*/*")) },
        )

        SecurePasswordField(
            show = s.showPassword,
            onToggleShow = { s.showPassword = !s.showPassword },
            onViewCreated = { s.passwordView = it },
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                Text(stringResource(R.string.hide_method_title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.SI_UNIWARD,
                    title = stringResource(R.string.algo_si_uniward_title),
                    subtitle = stringResource(R.string.algo_si_uniward_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.SI_UNIWARD },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD,
                    title = stringResource(R.string.algo_j_uniward_title),
                    subtitle = stringResource(R.string.algo_j_uniward_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.PLAIN_UNIWARD },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.F5,
                    title = stringResource(R.string.algo_f5_title),
                    subtitle = stringResource(R.string.algo_f5_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.F5 },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.ADAPTIVE,
                    title = stringResource(R.string.algo_adaptive_title),
                    subtitle = stringResource(R.string.algo_adaptive_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.ADAPTIVE },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.MATCHING,
                    title = stringResource(R.string.algo_lsb_matching_title),
                    subtitle = stringResource(R.string.algo_lsb_matching_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.MATCHING },
                )
                AlgorithmOption(
                    selected = s.algorithm == StegoEngine.Algorithm.WAV,
                    title = stringResource(R.string.algo_audio_wav_title),
                    subtitle = stringResource(R.string.algo_audio_wav_subtitle),
                    onClick = { s.algorithm = StegoEngine.Algorithm.WAV },
                )

                if (s.algorithm == StegoEngine.Algorithm.SI_UNIWARD) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.label_jpeg_quality, s.jpegQuality), fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = s.jpegQuality.toFloat(),
                        onValueChange = { s.jpegQuality = it.toInt() },
                        valueRange = 50f..100f,
                    )
                    Text(
                        stringResource(R.string.hint_jpeg_quality),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (s.algorithm == StegoEngine.Algorithm.ADAPTIVE || s.algorithm == StegoEngine.Algorithm.MATCHING) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { s.showAdvanced = !s.showAdvanced }) {
                        Text(if (s.showAdvanced) stringResource(R.string.btn_advanced_expanded) else stringResource(R.string.btn_advanced_collapsed))
                    }
                    if (s.showAdvanced) {
                        if (s.algorithm == StegoEngine.Algorithm.ADAPTIVE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.label_cluster_changes), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.hint_cluster_changes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = s.adaptiveCmd, onCheckedChange = { s.adaptiveCmd = it })
                            }
                            if (s.adaptiveCmd) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.label_clustering_strength, "%.1f".format(s.adaptiveCmdMu)))
                                Slider(
                                    value = s.adaptiveCmdMu.toFloat(),
                                    onValueChange = { s.adaptiveCmdMu = it.toDouble() },
                                    valueRange = 1f..9f,
                                    steps = 7,
                                )
                            }
                        } else { // LSB matching
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.label_bits_per_channel, s.lsbBits))
                            Slider(
                                value = s.lsbBits.toFloat(),
                                onValueChange = { s.lsbBits = it.toInt() },
                                valueRange = 1f..8f,
                                steps = 6,
                            )
                            Text(
                                stringResource(R.string.hint_bits_per_channel),
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
                    title = stringResource(R.string.label_compress_payload),
                    subtitle = stringResource(R.string.hint_compress_payload),
                    checked = s.useCompression,
                    onCheckedChange = { s.useCompression = it },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = stringResource(R.string.label_use_aes256),
                    subtitle = stringResource(R.string.hint_use_aes256),
                    checked = s.useAes256,
                    onCheckedChange = { s.useAes256 = it },
                )
            }
        }
        if (splitEligible) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.label_split_across_covers),
                        subtitle = stringResource(R.string.hint_split_across_covers),
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
                    Text(stringResource(R.string.label_store_filename), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.hint_store_filename),
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
                    stringResource(R.string.note_jpeg_share)
                StegoEngine.Algorithm.WAV ->
                    stringResource(R.string.note_wav_share)
                else ->
                    stringResource(R.string.note_png_share)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrimaryActionButton(
            label = stringResource(R.string.btn_hide),
            busy = s.busy,
            onClick = { runHide() },
            progress = s.progress,
            startedAtMs = s.startedAtMs,
        )

        s.result?.let { r ->
            OutputResultCard(
                name = r.name,
                onSave = { saveOutput.launch(r.name) },
                onShare = { shareResult() },
            )
        }
    }
}
