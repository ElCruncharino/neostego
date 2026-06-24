/*
 * "Hide data" screen — the Compose port of the Swing EmbedPanel, wired to :core. Same fields a
 * Swing user expects (message, cover, output, algorithm, encryption, password) in the modern
 * card-based design shared with the Android app, plus an inline description of the chosen algorithm.
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import com.elcruncharino.neostego.compose.engine.EmbedRequest
import com.elcruncharino.neostego.compose.engine.embed
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup

private val ENCRYPTION = listOf("None", "AES128", "AES256")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HideScreen(algorithms: List<AlgoInfo>) {
    var messageFile by remember { mutableStateOf<String?>(null) }
    var coverFile by remember { mutableStateOf<String?>(null) }
    var outputFile by remember { mutableStateOf<String?>(null) }
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull()) }
    var algoOpen by remember { mutableStateOf(false) }
    var encIndex by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Hide secret data inside an innocuous cover file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilePickCard("Message file", messageFile, "The secret file to hide") {
            pickFile(save = false)?.let { messageFile = it }
        }
        val coverExts = algorithm?.coverExtensions.orEmpty()
        val stegoExts = algorithm?.stegoExtensions.orEmpty()
        FilePickCard(
            "Cover file",
            coverFile,
            if (coverExts.isEmpty()) "An image or audio file to hide the message in" else "Allowed: ${coverExts.joinToString(", ")}",
        ) {
            pickFile(save = false, extensions = coverExts, filterLabel = "Cover files")?.let { coverFile = it }
        }
        FilePickCard(
            "Output stego file",
            outputFile,
            if (stegoExts.isEmpty()) "Where to save the result" else "Saved as: ${stegoExts.joinToString(", ")}",
        ) {
            pickFile(save = true, extensions = stegoExts, filterLabel = "Stego files")?.let { outputFile = it }
        }

        SectionLabel("Algorithm")
        ExposedDropdownMenuBox(expanded = algoOpen, onExpandedChange = { algoOpen = it }) {
            OutlinedTextField(
                value = algorithm?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Algorithm") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = algoOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = algoOpen, onDismissRequest = { algoOpen = false }) {
                algorithms.forEach { info ->
                    DropdownMenuItem(text = { Text(info.name) }, onClick = { algorithm = info; algoOpen = false })
                }
            }
        }
        algorithm?.let { AlgorithmDescriptionCard(it) }

        SectionLabel("Encryption")
        SegmentedButtonGroup(ENCRYPTION, encIndex, onSelect = { encIndex = it })

        SecurePasswordField(value = password, onValueChange = { password = it }, show = showPw, onToggleShow = { showPw = !showPw })

        PrimaryActionButton("Hide data", busy = busy, onClick = {
            busy = true
            result = null
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
                        ),
                    )
                }
                busy = false
            }.start()
        })

        result?.let { ResultCard(it) }
    }
}

@Composable
private fun AlgorithmDescriptionCard(info: AlgoInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.name, fontWeight = FontWeight.SemiBold)
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultCard(result: Result<String>) {
    val success = result.isSuccess
    val container = if (success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (success) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (success) "Done" else "Failed", fontWeight = FontWeight.SemiBold, color = content)
            Text(
                result.fold({ "Wrote stego file to $it" }, { it.message ?: it.toString() }),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}
