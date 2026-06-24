/*
 * "Hide data" screen — the Compose port of the Swing EmbedPanel, wired to :core. Message/cover/output
 * pickers, algorithm + advanced options, a capacity indicator, encryption with confirm-password, and
 * real embed progress.
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
import com.elcruncharino.neostego.compose.engine.fileSizeBytes
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.ui.AdvancedOptionsPanel
import com.elcruncharino.neostego.compose.ui.AlgorithmSelector
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup

private val ENCRYPTION = listOf("None", "AES128", "AES256")

@Composable
fun HideScreen(algorithms: List<AlgoInfo>) {
    var messageFile by remember { mutableStateOf<String?>(null) }
    var coverFile by remember { mutableStateOf<String?>(null) }
    var outputFile by remember { mutableStateOf<String?>(null) }
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

    // Capacity of the chosen cover for the chosen image algorithm (recomputed only when inputs change).
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

        FilePickCard("Message file", messageFile, "The secret file to hide", onFileDropped = { messageFile = it }) {
            pickFile(save = false)?.let { messageFile = it }
        }
        val coverExts = algorithm?.coverExtensions.orEmpty()
        val stegoExts = algorithm?.stegoExtensions.orEmpty()
        FilePickCard(
            "Cover file",
            coverFile,
            if (coverExts.isEmpty()) "An image or audio file to hide the message in" else "Allowed: ${coverExts.joinToString(", ")}",
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

        PrimaryActionButton("Hide data", busy = busy, progress = progress, onClick = {
            if (encIndex != 0 && password != confirmPassword) {
                result = Result.failure(IllegalArgumentException("Passwords do not match."))
            } else {
                busy = true
                result = null
                progress = 0f
                Thread {
                    result = runCatching {
                        embed(
                            EmbedRequest(
                                algorithm = algorithm?.name.orEmpty(),
                                messageFile = messageFile.orEmpty(),
                                coverFile = coverFile.orEmpty(),
                                outputFile = outputFile.orEmpty(),
                                encryptionAlgorithm = if (encIndex == 0) null else ENCRYPTION[encIndex],
                                password = password,
                                options = options,
                            ),
                        ) { f -> progress = f.toFloat() }
                    }
                    busy = false
                    progress = null
                }.start()
            }
        })

        result?.let { ResultCard(it) { path -> "Wrote stego file to $path" } }
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

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.1f KB".format(b / 1_000.0)
    else -> "$b B"
}
