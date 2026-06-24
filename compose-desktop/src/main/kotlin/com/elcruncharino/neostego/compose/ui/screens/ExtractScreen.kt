/*
 * "Extract data" screen — recovers a hidden message from a stego file into a chosen folder. The
 * algorithm is auto-detected (the stego file does not record it), so there is no algorithm picker;
 * the user supplies the stego file, an output folder, and the password (if one was used).
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.extract
import com.elcruncharino.neostego.compose.engine.pickDirectory
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SecurePasswordField

@Composable
fun ExtractScreen() {
    var stegoFile by remember { mutableStateOf<String?>(null) }
    var outputDir by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Recover a hidden message from a stego file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilePickCard("Stego file", stegoFile, "The image or audio file that contains hidden data") {
            pickFile(save = false)?.let { stegoFile = it }
        }
        FilePickCard("Output folder", outputDir, "Where to save the extracted message") {
            pickDirectory()?.let { outputDir = it }
        }

        SecurePasswordField(value = password, onValueChange = { password = it }, show = showPw, onToggleShow = { showPw = !showPw })

        PrimaryActionButton("Extract data", busy = busy, onClick = {
            busy = true
            result = null
            Thread {
                result = runCatching { extract(stegoFile.orEmpty(), password, outputDir.orEmpty()) }
                busy = false
            }.start()
        })

        result?.let { ResultCard(it) { path -> "Extracted message to $path" } }
    }
}
