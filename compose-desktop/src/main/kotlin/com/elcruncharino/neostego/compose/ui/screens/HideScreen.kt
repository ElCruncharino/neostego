/*
 * "Hide data" screen — the Compose port of the Swing EmbedPanel, wired to :core. Supports the four
 * cover modes at parity with the Swing UI: a single cover, a generated random-noise cover, batch
 * (same message into many covers), and split (one message spread across many covers). Plus algorithm
 * + advanced options, a capacity indicator, encryption with confirm-password, and real progress.
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AdvancedOptions
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import com.elcruncharino.neostego.compose.engine.EmbedRequest
import com.elcruncharino.neostego.compose.engine.coverCapacityBytes
import com.elcruncharino.neostego.compose.engine.embed
import com.elcruncharino.neostego.compose.engine.embedBatch
import com.elcruncharino.neostego.compose.engine.embedSplitCovers
import com.elcruncharino.neostego.compose.engine.fileSizeBytes
import com.elcruncharino.neostego.compose.engine.pickDirectory
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.ui.AdvancedOptionsPanel
import com.elcruncharino.neostego.compose.ui.AlgorithmSelector
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.MultiFilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.action_generate_cover_and_hide
import openstego.compose_desktop.generated.resources.action_hide_data
import openstego.compose_desktop.generated.resources.action_hide_in_each_cover
import openstego.compose_desktop.generated.resources.action_hide_in_n_covers
import openstego.compose_desktop.generated.resources.action_split_across_covers
import openstego.compose_desktop.generated.resources.action_split_across_n_covers
import openstego.compose_desktop.generated.resources.capacity_label
import openstego.compose_desktop.generated.resources.capacity_message_of_available
import openstego.compose_desktop.generated.resources.capacity_too_small
import openstego.compose_desktop.generated.resources.cover_file_label
import openstego.compose_desktop.generated.resources.encryption_none
import openstego.compose_desktop.generated.resources.error_passwords_do_not_match
import openstego.compose_desktop.generated.resources.hide_cover_file_hint_exts
import openstego.compose_desktop.generated.resources.hide_cover_file_hint_generic
import openstego.compose_desktop.generated.resources.hide_message_file_hint
import openstego.compose_desktop.generated.resources.hide_message_file_label
import openstego.compose_desktop.generated.resources.hide_output_folder_hint
import openstego.compose_desktop.generated.resources.hide_output_folder_label
import openstego.compose_desktop.generated.resources.hide_output_random_hint_generic
import openstego.compose_desktop.generated.resources.hide_output_stego_hint_generic
import openstego.compose_desktop.generated.resources.hide_output_stego_label
import openstego.compose_desktop.generated.resources.hide_screen_intro
import openstego.compose_desktop.generated.resources.mode_batch
import openstego.compose_desktop.generated.resources.mode_hint_batch
import openstego.compose_desktop.generated.resources.mode_hint_fell_back
import openstego.compose_desktop.generated.resources.mode_hint_random
import openstego.compose_desktop.generated.resources.mode_hint_single
import openstego.compose_desktop.generated.resources.mode_hint_split
import openstego.compose_desktop.generated.resources.mode_random_image
import openstego.compose_desktop.generated.resources.mode_single_cover
import openstego.compose_desktop.generated.resources.mode_split
import openstego.compose_desktop.generated.resources.multi_cover_change
import openstego.compose_desktop.generated.resources.multi_cover_choose
import openstego.compose_desktop.generated.resources.multi_cover_choose_hint
import openstego.compose_desktop.generated.resources.multi_cover_files_label
import openstego.compose_desktop.generated.resources.password_confirm_label
import openstego.compose_desktop.generated.resources.password_field_default_label
import openstego.compose_desktop.generated.resources.password_required_label
import openstego.compose_desktop.generated.resources.result_split_into_n_parts
import openstego.compose_desktop.generated.resources.result_wrote_n_stego_files
import openstego.compose_desktop.generated.resources.result_wrote_stego_file
import openstego.compose_desktop.generated.resources.saved_as_hint
import openstego.compose_desktop.generated.resources.section_cover_mode
import openstego.compose_desktop.generated.resources.section_encryption
import org.jetbrains.compose.resources.stringResource

// Cover modes, in selector order. Image-only modes are disabled when the algorithm isn't image-based.
private enum class Mode(val imageOnly: Boolean) {
    SINGLE(false),
    RANDOM(true),
    BATCH(false),
    SPLIT(true),
}

private val MODES = Mode.entries

@Composable
fun HideScreen(algorithms: List<AlgoInfo>) {
    var messageFile by remember { mutableStateOf<String?>(null) }
    var coverFile by remember { mutableStateOf<String?>(null) }
    var coverFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputFile by remember { mutableStateOf<String?>(null) }
    var outputDir by remember { mutableStateOf<String?>(null) }
    var modeIndex by remember { mutableStateOf(0) }
    // Default to Adaptive (the most secure image algorithm) when present.
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull { it.name == "Adaptive" } ?: algorithms.firstOrNull()) }
    // Default to AES128 encryption (which makes the password required).
    var encIndex by remember { mutableStateOf(1) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    var options by remember { mutableStateOf(AdvancedOptions()) }

    val isImageAlgo = algorithm != null && algorithm!!.coverExtensions.any { it in IMAGE_EXTS }
    val mode = MODES[modeIndex]
    // If the algorithm can't do the selected image-only mode, fall back to a single cover.
    val effectiveMode = if (mode.imageOnly && !isImageAlgo) Mode.SINGLE else mode

    val coverExts = algorithm?.coverExtensions.orEmpty()
    val stegoExts = algorithm?.stegoExtensions.orEmpty()

    // Capacity of the chosen cover for the chosen image algorithm (single-cover mode only).
    val capacity = remember(coverFile, algorithm?.name, options.maxBitsPerChannel) {
        val a = algorithm
        val c = coverFile
        if (a != null && c != null) coverCapacityBytes(a.name, c, options) else null
    }
    val messageSize = remember(messageFile) { messageFile?.let { fileSizeBytes(it) } }

    // Raw templates resolved here (in composition) so background-thread result callbacks can format
    // them with String.format without calling a @Composable off the main/composition context.
    val wroteStegoFileTemplate = stringResource(Res.string.result_wrote_stego_file)
    val wroteNStegoFilesTemplate = stringResource(Res.string.result_wrote_n_stego_files)
    val splitIntoNPartsTemplate = stringResource(Res.string.result_split_into_n_parts)
    val passwordsDoNotMatchMessage = stringResource(Res.string.error_passwords_do_not_match)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.hide_screen_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilePickCard(
            stringResource(Res.string.hide_message_file_label),
            messageFile,
            stringResource(Res.string.hide_message_file_hint),
            onFileDropped = { messageFile = it },
        ) {
            pickFile(save = false)?.let { messageFile = it }
        }

        SectionLabel(stringResource(Res.string.section_cover_mode))
        val modeLabels = MODES.map {
            when (it) {
                Mode.SINGLE -> stringResource(Res.string.mode_single_cover)
                Mode.RANDOM -> stringResource(Res.string.mode_random_image)
                Mode.BATCH -> stringResource(Res.string.mode_batch)
                Mode.SPLIT -> stringResource(Res.string.mode_split)
            }
        }
        SegmentedButtonGroup(
            modeLabels,
            modeIndex,
            onSelect = { modeIndex = it },
        )
        ModeHint(effectiveMode, isImageAlgo, mode != effectiveMode)

        when (effectiveMode) {
            Mode.SINGLE -> {
                FilePickCard(
                    stringResource(Res.string.cover_file_label),
                    coverFile,
                    if (coverExts.isEmpty()) {
                        stringResource(Res.string.hide_cover_file_hint_generic)
                    } else {
                        stringResource(Res.string.hide_cover_file_hint_exts, coverExts.joinToString(", "))
                    },
                    onFileDropped = { coverFile = it },
                ) {
                    pickFile(save = false, extensions = coverExts, filterLabel = "Cover files")?.let { coverFile = it }
                }
                if (capacity != null) CapacityCard(capacity, messageSize)
                FilePickCard(
                    stringResource(Res.string.hide_output_stego_label),
                    outputFile,
                    if (stegoExts.isEmpty()) {
                        stringResource(Res.string.hide_output_stego_hint_generic)
                    } else {
                        stringResource(Res.string.saved_as_hint, stegoExts.joinToString(", "))
                    },
                ) {
                    pickFile(save = true, extensions = stegoExts, filterLabel = "Stego files")?.let { outputFile = it }
                }
            }
            Mode.RANDOM -> {
                FilePickCard(
                    stringResource(Res.string.hide_output_stego_label),
                    outputFile,
                    if (stegoExts.isEmpty()) {
                        stringResource(Res.string.hide_output_random_hint_generic)
                    } else {
                        stringResource(Res.string.saved_as_hint, stegoExts.joinToString(", "))
                    },
                ) {
                    pickFile(save = true, extensions = stegoExts, filterLabel = "Stego files")?.let { outputFile = it }
                }
            }
            Mode.BATCH, Mode.SPLIT -> {
                MultiFilePickCard(
                    label = stringResource(Res.string.multi_cover_files_label),
                    emptyHint = stringResource(Res.string.multi_cover_choose_hint),
                    chooseLabel = stringResource(Res.string.multi_cover_choose),
                    changeLabel = stringResource(Res.string.multi_cover_change),
                    filterLabel = "Cover files",
                    files = coverFiles,
                    onChange = { coverFiles = it },
                    extensions = coverExts,
                )
                FilePickCard(stringResource(Res.string.hide_output_folder_label), outputDir, stringResource(Res.string.hide_output_folder_hint)) {
                    pickDirectory()?.let { outputDir = it }
                }
            }
        }

        AlgorithmSelector(algorithms, algorithm) { algorithm = it }
        algorithm?.let { algo -> AdvancedOptionsPanel(algo.optionsKind, options) { newOpts -> options = newOpts } }

        SectionLabel(stringResource(Res.string.section_encryption))
        val encryptionValues = listOf(null, "AES128", "AES256")
        val encryptionLabels = listOf(stringResource(Res.string.encryption_none), "AES128", "AES256")
        SegmentedButtonGroup(encryptionLabels, encIndex, onSelect = { encIndex = it })

        SecurePasswordField(
            value = password,
            onValueChange = { password = it },
            show = showPw,
            onToggleShow = { showPw = !showPw },
            label = if (encIndex == 0) stringResource(Res.string.password_field_default_label) else stringResource(Res.string.password_required_label),
        )
        if (encIndex != 0) {
            SecurePasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                show = showPw,
                onToggleShow = { showPw = !showPw },
                label = stringResource(Res.string.password_confirm_label),
            )
        }

        val actionLabel = when (effectiveMode) {
            Mode.SINGLE -> stringResource(Res.string.action_hide_data)
            Mode.RANDOM -> stringResource(Res.string.action_generate_cover_and_hide)
            Mode.BATCH -> if (coverFiles.isEmpty()) stringResource(Res.string.action_hide_in_each_cover) else stringResource(Res.string.action_hide_in_n_covers, coverFiles.size)
            Mode.SPLIT -> if (coverFiles.isEmpty()) stringResource(Res.string.action_split_across_covers) else stringResource(Res.string.action_split_across_n_covers, coverFiles.size)
        }
        PrimaryActionButton(actionLabel, busy = busy, progress = progress, onClick = {
            if (encIndex != 0 && password != confirmPassword) {
                result = Result.failure(IllegalArgumentException(passwordsDoNotMatchMessage))
            } else {
                busy = true
                result = null
                progress = 0f
                val enc = encryptionValues[encIndex]
                val algoName = algorithm?.name.orEmpty()
                val msg = messageFile.orEmpty()
                Thread {
                    result = runCatching {
                        when (effectiveMode) {
                            Mode.SINGLE -> embed(
                                EmbedRequest(algoName, msg, coverFile.orEmpty(), outputFile.orEmpty(), enc, password, options),
                            ) { f -> progress = f.toFloat() }.let { wroteStegoFileTemplate.format(it) }
                            Mode.RANDOM -> embed(
                                EmbedRequest(algoName, msg, "", outputFile.orEmpty(), enc, password, options, useRandomImage = true),
                            ) { f -> progress = f.toFloat() }.let { wroteStegoFileTemplate.format(it) }
                            Mode.BATCH -> {
                                val outs = embedBatch(algoName, msg, coverFiles, outputDir.orEmpty(), enc, password, options) { f -> progress = f.toFloat() }
                                wroteNStegoFilesTemplate.format(outs.size, outputDir.orEmpty())
                            }
                            Mode.SPLIT -> {
                                progress = null // splitter has no incremental progress
                                val outs = embedSplitCovers(algoName, msg, coverFiles, outputDir.orEmpty(), enc, password, options)
                                splitIntoNPartsTemplate.format(outs.size, outputDir.orEmpty())
                            }
                        }
                    }
                    busy = false
                    progress = null
                }.start()
            }
        })

        result?.let { ResultCard(it) { msg -> msg } }
    }
}

/** Short guidance for the selected mode (and a note if it was forced back to single cover). */
@Composable
private fun ModeHint(mode: Mode, isImageAlgo: Boolean, fellBack: Boolean) {
    val text = when {
        fellBack -> stringResource(Res.string.mode_hint_fell_back)
        mode == Mode.SINGLE -> stringResource(Res.string.mode_hint_single)
        mode == Mode.RANDOM -> stringResource(Res.string.mode_hint_random)
        mode == Mode.BATCH -> stringResource(Res.string.mode_hint_batch)
        else -> stringResource(Res.string.mode_hint_split)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CapacityCard(capacity: Long, messageSize: Long?) {
    val fits = messageSize == null || messageSize <= capacity
    val container = if (fits) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val content = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (fits) stringResource(Res.string.capacity_label, formatBytes(capacity)) else stringResource(Res.string.capacity_too_small),
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            if (messageSize != null) {
                Text(
                    stringResource(Res.string.capacity_message_of_available, formatBytes(messageSize), formatBytes(capacity)),
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
        }
    }
}

private val IMAGE_EXTS = setOf("png", "bmp", "gif", "jpg", "jpeg")

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.1f KB".format(b / 1_000.0)
    else -> "$b B"
}
