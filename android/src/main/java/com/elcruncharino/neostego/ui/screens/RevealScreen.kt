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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.R
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.FilePickCard
import com.elcruncharino.neostego.ui.components.OutputResultCard
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.SecurePasswordField
import com.elcruncharino.neostego.ui.components.ToggleRow
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

    val toastSavedFileTemplate = stringResource(R.string.toast_saved_file)
    val errorSavingTemplate = stringResource(R.string.error_saving)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val errorSharingTemplate = stringResource(R.string.error_sharing)
    val errorChooseStegoFile = stringResource(R.string.error_choose_stego_file)
    val errorFailedToReveal = stringResource(R.string.error_failed_to_reveal)
    val errorNeedTwoStegoFiles = stringResource(R.string.error_need_two_stego_files)

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun setResult(r: OutputResult?) {
        s.result?.bytes?.fill(0)
        s.result = r
    }

    val openStegoDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.stegoUri = it ?: s.stegoUri }
    val pickSplitStegoFiles = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            s.splitStegoUris.clear()
            s.splitStegoUris.addAll(uris)
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

    fun runRevealSplit() {
        val uris = s.splitStegoUris.toList()
        if (uris.size < 2) {
            toast(errorNeedTwoStegoFiles)
            return
        }
        // Each part is decoded (and released) one at a time inside extractSplit, so the risk mirrors
        // single-image reveal's - just per-part instead of once. Same check as runReveal, applied to
        // every part before starting rather than finding out mid-reassembly.
        for (uri in uris) {
            oversizeWarning(context, uri)?.let {
                toast(it)
                return
            }
        }
        val pw = com.elcruncharino.neostego.ui.components.readPasswordChars(s.passwordView)
        s.busy = true
        s.progress = null // reassembly runs across images; show an indeterminate bar
        s.startedAtMs = System.currentTimeMillis()
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    StegoEngine.extractSplit(
                        uris.map { readBytes(context, it) },
                        uris.map { displayName(context, it) },
                        pw,
                    )
                }
                val name = extracted.fileName.ifBlank { "revealed.dat" }
                setResult(OutputResult(name, mimeForName(name), extracted.data))
            } catch (e: OutOfMemoryError) {
                // Belt-and-suspenders: the pre-check above catches the common case, but several
                // moderately-sized parts can still add up under a lower per-device heap cap.
                snackbar.showSnackbar(errorFailedToReveal)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: errorFailedToReveal)
            } finally {
                pw?.fill(' ')
                s.busy = false
                s.progress = null
            }
        }
    }

    fun runReveal() {
        if (s.splitMode) {
            runRevealSplit()
            return
        }
        val stego = s.stegoUri
        if (stego == null) {
            toast(errorChooseStegoFile)
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
                snackbar.showSnackbar(e.message ?: errorFailedToReveal)
            } finally {
                pw?.fill(' ')
                s.busy = false
                s.progress = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.reveal_screen_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (s.splitMode) {
            FilePickCard(
                label = stringResource(R.string.label_stego_files_split),
                chosen = if (s.splitStegoUris.isEmpty()) {
                    null
                } else {
                    stringResource(R.string.split_images_selected, s.splitStegoUris.size)
                },
                hint = stringResource(R.string.hint_stego_files_split),
                onPick = { pickSplitStegoFiles.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        } else {
            FilePickCard(
                label = stringResource(R.string.label_stego_file),
                chosen = s.stegoUri?.let { displayName(context, it) },
                hint = stringResource(R.string.hint_stego_file),
                onPick = { openStegoDoc.launch(arrayOf("image/*", "audio/*")) },
            )
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                ToggleRow(
                    title = stringResource(R.string.label_reassemble_split),
                    subtitle = stringResource(R.string.hint_reassemble_split),
                    checked = s.splitMode,
                    onCheckedChange = { s.splitMode = it },
                )
            }
        }

        SecurePasswordField(
            show = s.showPassword,
            onToggleShow = { s.showPassword = !s.showPassword },
            onViewCreated = { s.passwordView = it },
        )

        PrimaryActionButton(
            label = stringResource(R.string.btn_reveal),
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
