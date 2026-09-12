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
import com.elcruncharino.neostego.ui.components.PasswordFieldWithConfirm
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.ToggleRow
import com.elcruncharino.neostego.ui.components.readPasswordChars
import com.elcruncharino.neostego.ui.util.OutputResult
import com.elcruncharino.neostego.ui.util.displayName
import com.elcruncharino.neostego.ui.util.fileSize
import com.elcruncharino.neostego.ui.util.humanBytes
import com.elcruncharino.neostego.ui.util.imageInfo
import com.elcruncharino.neostego.ui.util.mimeForName
import com.elcruncharino.neostego.ui.util.oversizeWarning
import com.elcruncharino.neostego.ui.util.readBytes
import com.elcruncharino.neostego.ui.util.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What kind of cover file an algorithm needs - used to drop a now-invalid cover on algorithm switch. */
private enum class CoverKind { AUDIO, JPEG_IMAGE, IMAGE }

private fun coverKindFor(algorithm: StegoEngine.Algorithm): CoverKind = when (algorithm) {
    StegoEngine.Algorithm.WAV -> CoverKind.AUDIO
    StegoEngine.Algorithm.PLAIN_UNIWARD, StegoEngine.Algorithm.F5 -> CoverKind.JPEG_IMAGE
    else -> CoverKind.IMAGE
}

/** One hiding-method radio option's static text, keyed by algorithm so the list can be data-driven. */
private data class AlgoEntry(val algorithm: StegoEngine.Algorithm, val titleRes: Int, val subtitleRes: Int)

private val ALGO_ENTRIES = listOf(
    AlgoEntry(StegoEngine.Algorithm.SI_UNIWARD, R.string.algo_si_uniward_title, R.string.algo_si_uniward_subtitle),
    AlgoEntry(StegoEngine.Algorithm.PLAIN_UNIWARD, R.string.algo_j_uniward_title, R.string.algo_j_uniward_subtitle),
    AlgoEntry(StegoEngine.Algorithm.F5, R.string.algo_f5_title, R.string.algo_f5_subtitle),
    AlgoEntry(StegoEngine.Algorithm.ADAPTIVE, R.string.algo_adaptive_title, R.string.algo_adaptive_subtitle),
    AlgoEntry(StegoEngine.Algorithm.MATCHING, R.string.algo_lsb_matching_title, R.string.algo_lsb_matching_subtitle),
    AlgoEntry(StegoEngine.Algorithm.WAV, R.string.algo_audio_wav_title, R.string.algo_audio_wav_subtitle),
)

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
    val errorChooseCoverFile = stringResource(R.string.error_choose_cover_file)
    val errorPasswordsDoNotMatch = stringResource(R.string.error_passwords_do_not_match)

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun setResult(r: OutputResult?) {
        s.result?.bytes?.fill(0)
        s.result = r
    }

    // A typo'd password would silently embed with the wrong key and be unrecoverable, so this is
    // checked before every hide (matching Swing and compose-desktop, which both enforce it too) -
    // except while the password is shown in plaintext, where there's nothing left to confirm and no
    // confirm field is even on screen (see PasswordFieldWithConfirm).
    fun passwordsMatch(): Boolean {
        if (s.showPassword) return true
        val pw = readPasswordChars(s.passwordView)
        val confirm = readPasswordChars(s.confirmPasswordView)
        // Unconditional, matching Swing's Arrays.equals(password, confPassword): a blank primary
        // field only counts as a match against an equally-blank confirm field, so stray/mistyped
        // text left in confirm still blocks (rather than being silently ignored).
        val ok = (pw ?: CharArray(0)).contentEquals(confirm ?: CharArray(0))
        pw?.fill(' ')
        confirm?.fill(' ')
        if (!ok) toast(errorPasswordsDoNotMatch)
        return ok
    }

    // Split mode's cover picker still filters by the selected algorithm (unlike single-cover mode,
    // which is now cover-first - see the LaunchedEffect below), so a switch there can invalidate
    // already-picked covers.
    fun selectAlgorithm(algorithm: StegoEngine.Algorithm) {
        if (s.splitMode && coverKindFor(algorithm) != coverKindFor(s.algorithm)) {
            s.splitCovers.clear()
        }
        s.algorithm = algorithm
    }

    // Split output is written part-by-part through a chain of save dialogs; these track that chain.
    var splitParts by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var splitPartIndex by remember { mutableStateOf(-1) }

    val splitEligible = s.algorithm in StegoEngine.SPLIT_ELIGIBLE_ALGORITHMS
    if (!splitEligible && s.splitMode) s.splitMode = false

    val options = StegoEngine.Options(s.jpegQuality, s.adaptiveCmd, s.adaptiveCmdMu, s.lsbBits, s.useCompression, s.useAes256)

    // Single-cover mode: the cover picked determines which algorithms are even offered (see
    // StegoEngine.algorithmsFor). Split mode keeps the older algorithm-first flow further below.
    val eligibleAlgorithms = if (s.splitMode) StegoEngine.SPLIT_ELIGIBLE_ALGORITHMS else StegoEngine.algorithmsFor(s.coverIsJpeg)

    // Detects the cover's real format and computes every eligible algorithm's capacity for it in one
    // pass, then nudges the current algorithm onto an eligible one if the cover just made it invalid.
    LaunchedEffect(s.coverUri, s.splitMode, s.lsbBits, s.jpegQuality) {
        if (s.splitMode) return@LaunchedEffect
        val uri = s.coverUri
        if (uri == null) {
            s.coverIsJpeg = null
            s.capacityByAlgorithm = emptyMap()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val info = imageInfo(context, uri)
            s.coverIsJpeg = info?.isJpeg
            // algorithmsFor(non-null isJpeg) only ever returns image algorithms, so no further
            // filtering is needed before computing each one's capacity for this cover.
            s.capacityByAlgorithm = if (info == null) {
                emptyMap()
            } else {
                StegoEngine.algorithmsFor(info.isJpeg)
                    .associateWith { algo -> runCatching { StegoEngine.capacityBytes(algo, info.width, info.height, options) }.getOrDefault(0) }
            }
        }
        val eligible = StegoEngine.algorithmsFor(s.coverIsJpeg)
        if (s.algorithm !in eligible) {
            s.algorithm = eligible.firstOrNull { it == StegoEngine.Algorithm.ADAPTIVE } ?: eligible.first()
        }
    }

    // Size of the file to hide, used to gray out algorithms this cover can't fit it in.
    LaunchedEffect(s.messageUri) {
        val uri = s.messageUri
        s.messageSize = uri?.let { withContext(Dispatchers.IO) { fileSize(context, it) } }
    }

    // Single cover: one generic picker for either an image or a WAV file (mirrors Reveal's stego-file
    // picker), since which kind was picked is now detected afterward instead of chosen upfront.
    val openCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.coverUri = it ?: s.coverUri }
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

    // --- Split output: one stego image per cover (PNG or JPEG, per algorithm), written through a
    // sequence of save dialogs. "*/*" (matching saveOutput above) sidesteps CreateDocument's MIME type
    // being fixed at launcher-creation time, since the actual extension is driven by the filename passed
    // to launch() below, not this contract argument.
    val saveSplitPart = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
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
            parts.isNotEmpty() && idx in parts.indices -> {
                val ext = StegoEngine.outputName(s.algorithm).substringAfterLast('.')
                saveSplitPart.launch("stego_part${idx + 1}.$ext")
            }
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
        if (!passwordsMatch()) return
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
        if (cover == null) {
            toast(errorChooseCoverFile)
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
        if (!passwordsMatch()) return
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

        if (s.splitMode) {
            // Split keeps the older algorithm-first flow: the selected algorithm decides what the
            // multi-cover picker's media-type filter allows.
            val coverKind = coverKindFor(s.algorithm)
            val needsJpegCover = coverKind == CoverKind.JPEG_IMAGE
            // The photo picker's media type is fixed at launch time only (unlike CreateDocument's MIME
            // type, which is fixed at launcher creation), so this can vary per algorithm freely: F5 and
            // PLAIN_UNIWARD embed into an already-compressed JPEG (a PNG cover for them is just wrong, not
            // merely undesirable), so only offer JPEGs; everything else keeps the existing broad filter,
            // since PNG/BMP/WEBP are all valid precovers for the spatial and SI-UNIWARD algorithms.
            val coverMediaType = if (needsJpegCover) {
                ActivityResultContracts.PickVisualMedia.SingleMimeType("image/jpeg")
            } else {
                ActivityResultContracts.PickVisualMedia.ImageOnly
            }
            FilePickCard(
                label = if (needsJpegCover) {
                    stringResource(R.string.label_cover_images_split_jpeg)
                } else {
                    stringResource(R.string.label_cover_images_split)
                },
                chosen = if (s.splitCovers.isEmpty()) null else stringResource(R.string.split_images_selected, s.splitCovers.size),
                hint = if (needsJpegCover) {
                    stringResource(R.string.hint_split_covers_jpeg)
                } else {
                    stringResource(R.string.hint_split_covers)
                },
                onPick = { pickCovers.launch(PickVisualMediaRequest(coverMediaType)) },
            )
        } else {
            // Single-cover mode is cover-first: pick the file, then the LaunchedEffect above detects
            // its real format and the hiding-method list below filters to what it actually supports.
            FilePickCard(
                label = stringResource(R.string.label_cover_file),
                chosen = s.coverUri?.let { displayName(context, it) },
                hint = stringResource(R.string.hint_cover_file),
                onPick = { openCover.launch(arrayOf("image/*", "audio/*")) },
            )
            s.capacityByAlgorithm[s.algorithm]?.let {
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

        PasswordFieldWithConfirm(
            show = s.showPassword,
            onToggleShow = { s.showPassword = !s.showPassword },
            onViewCreated = { s.passwordView = it },
            onConfirmViewCreated = { s.confirmPasswordView = it },
        )

        // Single-cover mode: nothing to show until a cover is picked (see hint_cover_file above).
        if (s.splitMode || s.coverUri != null) {
            val tooSmallHint = stringResource(R.string.hint_algo_too_small)
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                    Text(stringResource(R.string.hide_method_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val visibleEntries = if (s.splitMode) ALGO_ENTRIES else ALGO_ENTRIES.filter { it.algorithm in eligibleAlgorithms }
                    for (entry in visibleEntries) {
                        // Split mode doesn't know the message size per part, so it never grays out options.
                        val capacity = s.capacityByAlgorithm[entry.algorithm]
                        val tooSmall = !s.splitMode && s.messageSize != null && capacity != null && s.messageSize!! > capacity
                        AlgorithmOption(
                            selected = s.algorithm == entry.algorithm,
                            title = stringResource(entry.titleRes),
                            subtitle = if (tooSmall) tooSmallHint else stringResource(entry.subtitleRes),
                            enabled = !tooSmall,
                            onClick = { selectAlgorithm(entry.algorithm) },
                        )
                    }

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
