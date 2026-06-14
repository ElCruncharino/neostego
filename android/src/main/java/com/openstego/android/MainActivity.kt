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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StegoScreen()
                }
            }
        }
    }
}

private fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Unable to read selected file")

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IllegalStateException("Unable to write to selected location")
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment ?: "file"
}

@Composable
fun StegoScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 = Hide, 1 = Reveal
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var messageUri by remember { mutableStateOf<Uri?>(null) }
    var stegoUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var useMatching by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val openCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { coverUri = it }
    val openMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { messageUri = it }
    val openStego = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { stegoUri = it }

    val saveStego = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingBytes
        status = if (uri != null && bytes != null) {
            try {
                writeBytes(context, uri, bytes); "Saved stego image."
            } catch (e: Exception) {
                "Error saving: ${e.message ?: e}"
            }
        } else "Save cancelled."
    }
    val saveExtract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        status = if (uri != null && bytes != null) {
            try {
                writeBytes(context, uri, bytes); "Saved extracted file."
            } catch (e: Exception) {
                "Error saving: ${e.message ?: e}"
            }
        } else "Save cancelled."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("OpenStego", style = MaterialTheme.typography.headlineSmall)

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; status = "" }, text = { Text("Hide") })
            Tab(selected = tab == 1, onClick = { tab = 1; status = "" }, text = { Text("Reveal") })
        }

        if (tab == 0) {
            Button(onClick = { openCover.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(coverUri?.let { "Cover: " + displayName(context, it) } ?: "Choose cover image")
            }
            Button(onClick = { openMessage.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(messageUri?.let { "File: " + displayName(context, it) } ?: "Choose file to hide")
            }
        } else {
            Button(onClick = { openStego.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stegoUri?.let { "Stego: " + displayName(context, it) } ?: "Choose stego image")
            }
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (tab == 0) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = useMatching, onCheckedChange = { useMatching = it })
                Text("Use LSB matching (harder to detect)")
            }

            Button(
                onClick = {
                    status = "Working..."
                    try {
                        val cover = readBytes(context, coverUri ?: error("Choose a cover image"))
                        val message = readBytes(context, messageUri ?: error("Choose a file to hide"))
                        val coverName = displayName(context, coverUri!!)
                        val msgName = displayName(context, messageUri!!)
                        pendingBytes = StegoEngine.embed(
                            useMatching, message, msgName, cover, coverName, password.ifBlank { null }
                        )
                        status = "Embedded. Choose where to save the stego image."
                        saveStego.launch("stego.png")
                    } catch (e: Exception) {
                        status = "Error: ${e.message ?: e}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Hide & Save") }
        } else {
            Button(
                onClick = {
                    status = "Working..."
                    try {
                        val data = readBytes(context, stegoUri ?: error("Choose a stego image"))
                        val name = displayName(context, stegoUri!!)
                        val extracted = StegoEngine.extract(data, name, password.ifBlank { null })
                        pendingBytes = extracted.data
                        status = "Revealed '${extracted.fileName}'. Choose where to save."
                        saveExtract.launch(extracted.fileName.ifBlank { "extracted.dat" })
                    } catch (e: Exception) {
                        status = "Error: ${e.message ?: e}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reveal & Save") }
        }

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
