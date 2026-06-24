/*
 * Compose Desktop prototype of NeoStego's "Hide data" screen, wired to the real :core engine.
 * Goal: judge GPU-rendered resize smoothness vs the Swing UI, and gauge port effort.
 */
package com.elcruncharino.neostego.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.openstego.desktop.OpenStego
import com.openstego.desktop.util.CommonUtil
import com.openstego.desktop.util.PluginManager
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private fun pickFile(save: Boolean): String? {
    var result: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser()
        val outcome = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (outcome == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
    }
    return result
}

/** Mirrors the Swing embed flow (OpenStegoUI.embedData) against :core. */
private fun embed(algo: String, msgPath: String, coverPath: String, outPath: String, password: String): String {
    val plugin = PluginManager.getPluginByName(algo) ?: return "Unknown algorithm: $algo"
    plugin.resetConfig()
    val config = plugin.config
    config.setUseCompression(true)
    val usePassword = password.isNotEmpty()
    config.setUseEncryption(usePassword)
    if (usePassword) {
        config.setEncryptionAlgorithm("AES128")
        config.setPassword(password)
    }
    val stego = OpenStego(plugin, config)
    val data = stego.embedData(File(msgPath), File(coverPath), outPath)
    CommonUtil.writeFile(data, outPath)
    return "OK — wrote ${data.size} bytes to $outPath"
}

@Composable
private fun FileRow(label: String, value: String, save: Boolean, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { pickFile(save)?.let(onChange) }, modifier = Modifier.padding(bottom = 4.dp)) {
            Text("Browse…")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideDataScreen(algorithms: List<String>) {
    var messageFile by remember { mutableStateOf("") }
    var coverFile by remember { mutableStateOf("") }
    var outputFile by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull() ?: "") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready.") }
    var algoExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Hide data in harmless looking files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        FileRow("Message file", messageFile, save = false) { messageFile = it }
        FileRow("Cover file", coverFile, save = false) { coverFile = it }
        FileRow("Output stego file", outputFile, save = true) { outputFile = it }

        ExposedDropdownMenuBox(expanded = algoExpanded, onExpandedChange = { algoExpanded = it }) {
            OutlinedTextField(
                value = algorithm,
                onValueChange = {},
                readOnly = true,
                label = { Text("Algorithm") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = algoExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = algoExpanded, onDismissRequest = { algoExpanded = false }) {
                algorithms.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { algorithm = name; algoExpanded = false })
                }
            }
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                status = "Working…"
                Thread {
                    status = try {
                        embed(algorithm, messageFile, coverFile, outputFile, password)
                    } catch (e: Throwable) {
                        "Error: ${e.message}"
                    }
                }.start()
            }) { Text("Hide data") }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun main() {
    PluginManager.loadPlugins()
    val algorithms = PluginManager.getDataHidingPlugins().map { it.name }
    application {
        Window(onCloseRequest = ::exitApplication, title = "NeoStego — Compose Desktop prototype") {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HideDataScreen(algorithms)
                }
            }
        }
    }
}
