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
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.action_extract_data
import openstego.compose_desktop.generated.resources.action_reassemble_and_extract
import openstego.compose_desktop.generated.resources.action_reassemble_n_parts
import openstego.compose_desktop.generated.resources.extract_hint_reassemble
import openstego.compose_desktop.generated.resources.extract_hint_single
import openstego.compose_desktop.generated.resources.extract_mode_reassemble_split
import openstego.compose_desktop.generated.resources.extract_mode_single_file
import openstego.compose_desktop.generated.resources.extract_output_folder_hint
import openstego.compose_desktop.generated.resources.extract_screen_intro
import openstego.compose_desktop.generated.resources.extract_stego_file_hint
import openstego.compose_desktop.generated.resources.extract_stego_file_label
import openstego.compose_desktop.generated.resources.hide_output_folder_label
import openstego.compose_desktop.generated.resources.result_extracted_message_to
import openstego.compose_desktop.generated.resources.section_source
import openstego.compose_desktop.generated.resources.selection_summary
import openstego.compose_desktop.generated.resources.split_parts_change
import openstego.compose_desktop.generated.resources.split_parts_choose
import openstego.compose_desktop.generated.resources.split_parts_choose_hint
import openstego.compose_desktop.generated.resources.split_parts_label
import org.jetbrains.compose.resources.stringResource

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

    val extractedMessageToTemplate = stringResource(Res.string.result_extracted_message_to)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.extract_screen_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel(stringResource(Res.string.section_source))
        val modeLabels = listOf(stringResource(Res.string.extract_mode_single_file), stringResource(Res.string.extract_mode_reassemble_split))
        SegmentedButtonGroup(modeLabels, modeIndex, onSelect = { modeIndex = it })
        Text(
            if (reassemble) {
                stringResource(Res.string.extract_hint_reassemble)
            } else {
                stringResource(Res.string.extract_hint_single)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (reassemble) {
            SplitPartsCard(stegoParts) { stegoParts = it }
        } else {
            FilePickCard(
                stringResource(Res.string.extract_stego_file_label),
                stegoFile,
                stringResource(Res.string.extract_stego_file_hint),
                onFileDropped = { stegoFile = it },
            ) {
                pickFile(save = false)?.let { stegoFile = it }
            }
        }
        FilePickCard(stringResource(Res.string.hide_output_folder_label), outputDir, stringResource(Res.string.extract_output_folder_hint)) {
            pickDirectory()?.let { outputDir = it }
        }

        SecurePasswordField(value = password, onValueChange = { password = it }, show = showPw, onToggleShow = { showPw = !showPw })

        val actionLabel = if (reassemble) {
            if (stegoParts.isEmpty()) stringResource(Res.string.action_reassemble_and_extract) else stringResource(Res.string.action_reassemble_n_parts, stegoParts.size)
        } else {
            stringResource(Res.string.action_extract_data)
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

        result?.let { ResultCard(it) { path -> extractedMessageToTemplate.format(path) } }
    }
}

/** Multi-select input for the parts of a split payload. */
@Composable
private fun SplitPartsCard(parts: List<String>, onChange: (List<String>) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.split_parts_label), fontWeight = FontWeight.SemiBold)
            Text(
                if (parts.isEmpty()) {
                    stringResource(Res.string.split_parts_choose_hint)
                } else {
                    stringResource(Res.string.selection_summary, parts.size, parts.joinToString(", ") { it.substringAfterLast('/') })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chooseLabel = stringResource(Res.string.split_parts_choose)
            val changeLabel = stringResource(Res.string.split_parts_change)
            OutlinedButton(onClick = {
                val picked = pickFiles(filterLabel = "Stego parts")
                if (picked.isNotEmpty()) onChange(picked)
            }) { Text(if (parts.isEmpty()) chooseLabel else changeLabel) }
        }
    }
}
