/*
 * "Extract data" screen — recovers a hidden message from a stego file into a chosen folder. The
 * algorithm is auto-detected (the stego file does not record it), so there is no algorithm picker.
 * Two modes at parity with the Swing UI: a single stego file, or reassembling a split payload from
 * all of its parts. The user supplies the file(s), an output folder, and the password (if one was used).
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.extract
import com.elcruncharino.neostego.compose.engine.extractSplitFiles
import com.elcruncharino.neostego.compose.engine.pickDirectory
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.engine.pickFiles
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup

private val MODE_LABELS = listOf("Single file", "Reassemble split")

@Composable
fun ExtractScreen() {
    var stegoFile by remember { mutableStateOf<String?>(null) }
    var stegoParts by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputDir by remember { mutableStateOf<String?>(null) }
    var modeIndex by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    val reassemble = modeIndex == 1

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Recover a hidden message from a stego file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Source")
        SegmentedButtonGroup(MODE_LABELS, modeIndex, onSelect = { modeIndex = it })
        Text(
            if (reassemble) {
                "Select all parts of a split payload to reassemble the original message."
            } else {
                "Recover the message from a single stego file."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (reassemble) {
            SplitPartsCard(stegoParts) { stegoParts = it }
        } else {
            FilePickCard("Stego file", stegoFile, "Choose or drag the file with hidden data here", onFileDropped = { stegoFile = it }) {
                pickFile(save = false)?.let { stegoFile = it }
            }
        }
        FilePickCard("Output folder", outputDir, "Where to save the extracted message") {
            pickDirectory()?.let { outputDir = it }
        }

        SecurePasswordField(value = password, onValueChange = { password = it }, show = showPw, onToggleShow = { showPw = !showPw })

        val actionLabel = if (reassemble) {
            if (stegoParts.isEmpty()) "Reassemble & extract" else "Reassemble ${stegoParts.size} parts"
        } else {
            "Extract data"
        }
        PrimaryActionButton(actionLabel, busy = busy, onClick = {
            busy = true
            result = null
            val parts = stegoParts
            val single = stegoFile.orEmpty()
            val dir = outputDir.orEmpty()
            Thread {
                result = runCatching {
                    if (reassemble) {
                        extractSplitFiles(parts, password, dir)
                    } else {
                        extract(single, password, dir)
                    }
                }
                busy = false
            }.start()
        })

        result?.let { ResultCard(it) { path -> "Extracted message to $path" } }
    }
}

/** Multi-select input for the parts of a split payload. */
@Composable
private fun SplitPartsCard(parts: List<String>, onChange: (List<String>) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Split parts", fontWeight = FontWeight.SemiBold)
            Text(
                if (parts.isEmpty()) "Choose every part of the split" else "${parts.size} selected: " + parts.joinToString(", ") { it.substringAfterLast('/') },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                val picked = pickFiles(filterLabel = "Stego parts")
                if (picked.isNotEmpty()) onChange(picked)
            }) { Text(if (parts.isEmpty()) "Choose parts" else "Change parts") }
        }
    }
}
