/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenStegoTheme {
                StegoApp()
            }
        }
    }
}

private fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Unable to read the selected file")

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IllegalStateException("Unable to write to the selected location")
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment ?: "file"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(0) } // 0 = Hide, 1 = Reveal
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var messageUri by remember { mutableStateOf<Uri?>(null) }
    var stegoUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var useMatching by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val openCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { coverUri = it }
    val openMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { messageUri = it }
    val openStego = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { stegoUri = it }

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    val saveStego = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingBytes
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
                    snackbar.showSnackbar("Saved stego image")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        }
    }
    val saveExtract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
                    snackbar.showSnackbar("Saved revealed file")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        }
    }

    fun runHide() {
        val cover = coverUri
        val message = messageUri
        if (cover == null) { toast("Choose a cover image first"); return }
        if (message == null) { toast("Choose a file to hide first"); return }
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    StegoEngine.embed(
                        useMatching,
                        readBytes(context, message),
                        displayName(context, message),
                        readBytes(context, cover),
                        displayName(context, cover),
                        password.ifBlank { null }
                    )
                }
                pendingBytes = bytes
                saveStego.launch("stego.png")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                busy = false
            }
        }
    }

    fun runReveal() {
        val stego = stegoUri
        if (stego == null) { toast("Choose a stego image first"); return }
        busy = true
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    StegoEngine.extract(readBytes(context, stego), displayName(context, stego), password.ifBlank { null })
                }
                pendingBytes = extracted.data
                saveExtract.launch(extracted.fileName.ifBlank { "revealed.dat" })
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to reveal data")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("OpenStego") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Hide") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Reveal") })
            }

            Text(
                if (tab == 0) "Hide a file inside an image" else "Reveal a hidden file from an image",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tab == 0) {
                FilePickCard(
                    label = "Cover image",
                    chosen = coverUri?.let { displayName(context, it) },
                    hint = "The image the data will be hidden in",
                    onPick = { openCover.launch(arrayOf("image/*")) }
                )
                FilePickCard(
                    label = "File to hide",
                    chosen = messageUri?.let { displayName(context, it) },
                    hint = "Any file (document, photo, etc.)",
                    onPick = { openMessage.launch(arrayOf("*/*")) }
                )
            } else {
                FilePickCard(
                    label = "Stego image",
                    chosen = stegoUri?.let { displayName(context, it) },
                    hint = "An image that has data hidden in it",
                    onPick = { openStego.launch(arrayOf("image/*")) }
                )
            }

            PasswordField(
                password = password,
                onPasswordChange = { password = it },
                show = showPassword,
                onToggleShow = { showPassword = !showPassword }
            )

            if (tab == 0) {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Harder to detect", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Use LSB matching, which resists statistical steganalysis",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = useMatching, onCheckedChange = { useMatching = it })
                    }
                }
            }

            Button(
                onClick = { if (tab == 0) runHide() else runReveal() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Working...")
                } else {
                    Text(if (tab == 0) "Hide & Save" else "Reveal & Save")
                }
            }
        }
    }
}

@Composable
private fun FilePickCard(label: String, chosen: String?, hint: String, onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    chosen ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onPick) { Text(if (chosen == null) "Choose" else "Change") }
        }
    }
}

@Composable
private fun PasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    show: Boolean,
    onToggleShow: () -> Unit
) {
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Password (optional)") },
        singleLine = true,
        // Password keyboard type + no autocorrect so the IME (e.g. Gboard) does not alter the password
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = onToggleShow) { Text(if (show) "Hide" else "Show") }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
