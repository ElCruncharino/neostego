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
import com.elcruncharino.neostego.compose.engine.pickFiles
import com.elcruncharino.neostego.compose.ui.AdvancedOptionsPanel
import com.elcruncharino.neostego.compose.ui.AlgorithmSelector
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup

private val ENCRYPTION = listOf("None", "AES128", "AES256")

// Cover modes, in selector order. Image-only modes are disabled when the algorithm isn't image-based.
private enum class Mode(val label: String, val imageOnly: Boolean) {
    SINGLE("One cover", false),
    RANDOM("Random image", true),
    BATCH("Batch", false),
    SPLIT("Split", true),
}

private val MODES = Mode.entries
private val MODE_LABELS = MODES.map { it.label }

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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Hide secret data inside an innocuous cover file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilePickCard("Message file", messageFile, "Choose or drag your secret file to hide here", onFileDropped = { messageFile = it }) {
            pickFile(save = false)?.let { messageFile = it }
        }

        SectionLabel("Cover mode")
        SegmentedButtonGroup(
            MODE_LABELS,
            modeIndex,
            onSelect = { modeIndex = it },
        )
        ModeHint(effectiveMode, isImageAlgo, mode != effectiveMode)

        when (effectiveMode) {
            Mode.SINGLE -> {
                FilePickCard(
                    "Cover file",
                    coverFile,
                    if (coverExts.isEmpty()) "Choose or drag the cover file to hide it in here" else "Choose or drag a cover here (${coverExts.joinToString(", ")})",
                    onFileDropped = { coverFile = it },
                ) {
                    pickFile(save = false, extensions = coverExts, filterLabel = "Cover files")?.let { coverFile = it }
                }
                if (capacity != null) CapacityCard(capacity, messageSize)
                FilePickCard(
                    "Output stego file",
                    outputFile,
                    if (stegoExts.isEmpty()) "Where to save the result" else "Saved as: ${stegoExts.joinToString(", ")}",
                ) {
                    pickFile(save = true, extensions = stegoExts, filterLabel = "Stego files")?.let { outputFile = it }
                }
            }
            Mode.RANDOM -> {
                FilePickCard(
                    "Output stego file",
                    outputFile,
                    if (stegoExts.isEmpty()) "Where to save the generated image" else "Saved as: ${stegoExts.joinToString(", ")}",
                ) {
                    pickFile(save = true, extensions = stegoExts, filterLabel = "Stego files")?.let { outputFile = it }
                }
            }
            Mode.BATCH, Mode.SPLIT -> {
                MultiCoverCard(coverFiles, coverExts) { coverFiles = it }
                FilePickCard("Output folder", outputDir, "Where to save the stego files") {
                    pickDirectory()?.let { outputDir = it }
                }
            }
        }

        AlgorithmSelector(algorithms, algorithm) { algorithm = it }
        algorithm?.let { algo -> AdvancedOptionsPanel(algo.optionsKind, options) { newOpts -> options = newOpts } }

        SectionLabel("Encryption")
        SegmentedButtonGroup(ENCRYPTION, encIndex, onSelect = { encIndex = it })

        SecurePasswordField(
            value = password,
            onValueChange = { password = it },
            show = showPw,
            onToggleShow = { showPw = !showPw },
            label = if (encIndex == 0) "Password (optional)" else "Password (required for encryption)",
        )
        if (encIndex != 0) {
            SecurePasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                show = showPw,
                onToggleShow = { showPw = !showPw },
                label = "Confirm password",
            )
        }

        val actionLabel = when (effectiveMode) {
            Mode.SINGLE -> "Hide data"
            Mode.RANDOM -> "Generate cover & hide"
            Mode.BATCH -> if (coverFiles.isEmpty()) "Hide in each cover" else "Hide in ${coverFiles.size} covers"
            Mode.SPLIT -> if (coverFiles.isEmpty()) "Split across covers" else "Split across ${coverFiles.size} covers"
        }
        PrimaryActionButton(actionLabel, busy = busy, progress = progress, onClick = {
            if (encIndex != 0 && password != confirmPassword) {
                result = Result.failure(IllegalArgumentException("Passwords do not match."))
            } else {
                busy = true
                result = null
                progress = 0f
                val enc = if (encIndex == 0) null else ENCRYPTION[encIndex]
                val algoName = algorithm?.name.orEmpty()
                val msg = messageFile.orEmpty()
                Thread {
                    result = runCatching {
                        when (effectiveMode) {
                            Mode.SINGLE -> embed(
                                EmbedRequest(algoName, msg, coverFile.orEmpty(), outputFile.orEmpty(), enc, password, options),
                            ) { f -> progress = f.toFloat() }.let { "Wrote stego file to $it" }
                            Mode.RANDOM -> embed(
                                EmbedRequest(algoName, msg, "", outputFile.orEmpty(), enc, password, options, useRandomImage = true),
                            ) { f -> progress = f.toFloat() }.let { "Wrote stego file to $it" }
                            Mode.BATCH -> {
                                val outs = embedBatch(algoName, msg, coverFiles, outputDir.orEmpty(), enc, password, options) { f -> progress = f.toFloat() }
                                "Wrote ${outs.size} stego files to ${outputDir.orEmpty()}"
                            }
                            Mode.SPLIT -> {
                                progress = null // splitter has no incremental progress
                                val outs = embedSplitCovers(algoName, msg, coverFiles, outputDir.orEmpty(), enc, password, options)
                                "Split into ${outs.size} parts in ${outputDir.orEmpty()} — keep all parts to extract"
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
        fellBack -> "This algorithm isn't image-based, so only a single cover is available."
        mode == Mode.SINGLE -> "Hide the message in one cover file."
        mode == Mode.RANDOM -> "Generate a random-noise image as the cover — no cover file needed."
        mode == Mode.BATCH -> "Hide the same message separately in each chosen cover."
        else -> "Spread one message across several covers. All resulting parts are needed to extract it."
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Multi-select cover input for batch/split: a Choose button, the count, and the chosen file names. */
@Composable
private fun MultiCoverCard(covers: List<String>, coverExts: List<String>, onChange: (List<String>) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cover files", fontWeight = FontWeight.SemiBold)
            Text(
                if (covers.isEmpty()) "Choose two or more covers" else "${covers.size} selected: " + covers.joinToString(", ") { it.substringAfterLast('/') },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedButton(onClick = {
                val picked = pickFiles(extensions = coverExts, filterLabel = "Cover files")
                if (picked.isNotEmpty()) onChange(picked)
            }) { Text(if (covers.isEmpty()) "Choose covers" else "Change covers") }
        }
    }
}

@Composable
private fun CapacityCard(capacity: Long, messageSize: Long?) {
    val fits = messageSize == null || messageSize <= capacity
    val container = if (fits) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val content = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (fits) "Capacity: ~${formatBytes(capacity)}" else "Message too large for this cover",
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            if (messageSize != null) {
                Text("Message is ${formatBytes(messageSize)} of ~${formatBytes(capacity)} available.", style = MaterialTheme.typography.bodySmall, color = content)
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
