/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.FilePickCard
import com.elcruncharino.neostego.ui.components.OutputResultCard
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.SecurePasswordField
import com.elcruncharino.neostego.ui.util.OutputResult
import com.elcruncharino.neostego.ui.util.displayName
import com.elcruncharino.neostego.ui.util.mimeForName
import com.elcruncharino.neostego.ui.util.oversizeWarning
import com.elcruncharino.neostego.ui.util.readBytes
import com.elcruncharino.neostego.ui.util.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RevealScreen(appState: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = appState.snackbar
    val s = appState.reveal

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun setResult(r: OutputResult?) {
        s.result?.bytes?.fill(0)
        s.result = r
    }

    val openStegoDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.stegoUri = it ?: s.stegoUri }

    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val r = s.result
        if (uri != null && r != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, r.bytes) }
                    snackbar.showSnackbar("Saved ${r.name}")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
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
                context.startActivity(android.content.Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbar.showSnackbar("Unable to share: ${e.message ?: e}")
            }
        }
    }

    fun runReveal() {
        val stego = s.stegoUri
        if (stego == null) {
            toast("Choose a stego file first")
            return
        }
        oversizeWarning(context, stego)?.let {
            toast(it)
            return
        }
        val pw = com.elcruncharino.neostego.ui.components.readPasswordChars(s.passwordView)
        s.busy = true
        s.progress = null
        s.startedAtMs = System.currentTimeMillis()
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    StegoEngine.extract(
                        readBytes(context, stego),
                        displayName(context, stego),
                        pw,
                        onProgress = { f -> s.progress = f },
                    )
                }
                val name = extracted.fileName.ifBlank { "revealed.dat" }
                setResult(OutputResult(name, mimeForName(name), extracted.data))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to reveal data")
            } finally {
                pw?.fill(' ')
                s.busy = false
                s.progress = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Reveal a hidden file from a cover",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilePickCard(
            label = "Stego file",
            chosen = s.stegoUri?.let { displayName(context, it) },
            hint = "An image or WAV that has data hidden in it",
            onPick = { openStegoDoc.launch(arrayOf("image/*", "audio/*")) },
        )

        SecurePasswordField(
            show = s.showPassword,
            onToggleShow = { s.showPassword = !s.showPassword },
            onViewCreated = { s.passwordView = it },
        )

        PrimaryActionButton(
            label = "Reveal",
            busy = s.busy,
            onClick = { runReveal() },
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
