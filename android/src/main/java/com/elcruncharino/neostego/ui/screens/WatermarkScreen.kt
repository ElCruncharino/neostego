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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.AlgorithmOption
import com.elcruncharino.neostego.ui.components.FilePickCard
import com.elcruncharino.neostego.ui.components.OutputResultCard
import com.elcruncharino.neostego.ui.components.PrimaryActionButton
import com.elcruncharino.neostego.ui.components.SecurePasswordField
import com.elcruncharino.neostego.ui.components.SegmentedButtonGroup
import com.elcruncharino.neostego.ui.components.ToggleRow
import com.elcruncharino.neostego.ui.components.WatermarkVerdictCard
import com.elcruncharino.neostego.ui.components.readPasswordChars
import com.elcruncharino.neostego.ui.util.OutputResult
import com.elcruncharino.neostego.ui.util.displayName
import com.elcruncharino.neostego.ui.util.mimeForName
import com.elcruncharino.neostego.ui.util.readBytes
import com.elcruncharino.neostego.ui.util.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WatermarkScreen(appState: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = appState.snackbar
    val s = appState.watermark

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }
    fun setResult(r: OutputResult?) {
        s.result?.bytes?.fill(0)
        s.result = r
    }

    val openSig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { s.sigUri = it ?: s.sigUri }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { s.coverUri = it ?: s.coverUri }
    val pickMarked = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { s.markedUri = it ?: s.markedUri }

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

    LaunchedEffect(s.mode) {
        s.verdict = null
        setResult(null)
    }

    fun runGenerate() {
        val pw = readPasswordChars(s.passwordView)
        if (pw == null) {
            toast("Enter a password to key the signature")
            return
        }
        s.busy = true
        scope.launch {
            try {
                val sig = withContext(Dispatchers.IO) { StegoEngine.generateSignature(s.algo, pw) }
                setResult(OutputResult("watermark.sig", "application/octet-stream", sig))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to generate signature")
            } finally {
                pw.fill(' ')
                s.busy = false
            }
        }
    }

    fun runEmbed() {
        val cover = s.coverUri
        val sig = s.sigUri
        if (cover == null) {
            toast("Choose an image to watermark")
            return
        }
        if (sig == null) {
            toast("Choose the signature file")
            return
        }
        s.busy = true
        s.progress = null
        s.startedAtMs = System.currentTimeMillis()
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    StegoEngine.embedMark(
                        s.algo,
                        readBytes(context, sig),
                        readBytes(context, cover),
                        displayName(context, cover),
                        s.outputJpeg,
                        s.jpegQuality,
                        onProgress = { f -> s.progress = f },
                    )
                }
                val name = StegoEngine.markOutputName(s.outputJpeg)
                setResult(OutputResult(name, mimeForName(name), bytes))
            } catch (e: OutOfMemoryError) {
                // Watermarking decodes the image at full resolution and runs a wavelet transform over it, so a very
                // large photo can exhaust the heap. Recover gracefully instead of letting the Error crash the app.
                snackbar.showSnackbar("This image is too large to watermark on this device. Try a smaller image.")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to embed watermark")
            } finally {
                s.busy = false
                s.progress = null
            }
        }
    }

    fun runVerify() {
        val marked = s.markedUri
        val sig = s.sigUri
        if (marked == null) {
            toast("Choose the image to check")
            return
        }
        if (sig == null) {
            toast("Choose the signature file")
            return
        }
        s.busy = true
        s.verdict = null
        s.progress = null
        s.startedAtMs = System.currentTimeMillis()
        scope.launch {
            try {
                s.verdict = withContext(Dispatchers.IO) {
                    StegoEngine.checkMark(
                        s.algo,
                        readBytes(context, marked),
                        displayName(context, marked),
                        readBytes(context, sig),
                        onProgress = { f -> s.progress = f },
                    )
                }
            } catch (e: OutOfMemoryError) {
                snackbar.showSnackbar("This image is too large to check on this device. Try a smaller image.")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to verify watermark")
            } finally {
                s.busy = false
                s.progress = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SegmentedButtonGroup(
            options = listOf("Generate", "Embed", "Verify"),
            selectedIndex = s.mode,
            onSelect = { s.mode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            when (s.mode) {
                0 -> "Create a password-keyed signature file to watermark with"
                1 -> "Embed a signature into an image as a robust watermark"
                else -> "Check whether an image carries a watermark"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                Text("Algorithm", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.DWT_SVD,
                    title = "DWT-SVD (recommended)",
                    subtitle = "Modern blind, multi-bit watermark. Survives JPEG re-compression, noise, blur and small crops.",
                    onClick = { s.algo = StegoEngine.WmAlgorithm.DWT_SVD },
                )
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.DUGAD,
                    title = "DWT-Dugad",
                    subtitle = "Classic wavelet spread-spectrum watermark detected by correlation.",
                    onClick = { s.algo = StegoEngine.WmAlgorithm.DUGAD },
                )
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.XIE,
                    title = "DWT-Xie",
                    subtitle = "Wavelet watermark embedded in the approximation sub-band.",
                    onClick = { s.algo = StegoEngine.WmAlgorithm.XIE },
                )
            }
        }

        when (s.mode) {
            0 -> {
                SecurePasswordField(
                    show = s.showPassword,
                    onToggleShow = { s.showPassword = !s.showPassword },
                    onViewCreated = { s.passwordView = it },
                )
                Text(
                    "The same password always produces the same signature. Save the .sig file - you need it to " +
                        "embed and to verify.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            1 -> {
                FilePickCard(
                    label = "Image to watermark",
                    chosen = s.coverUri?.let { displayName(context, it) },
                    hint = "The image the watermark is embedded into",
                    onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                FilePickCard(
                    label = "Signature file",
                    chosen = s.sigUri?.let { displayName(context, it) },
                    hint = "The .sig produced by Generate",
                    onPick = { openSig.launch(arrayOf("*/*")) },
                )
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        ToggleRow(
                            title = "Output JPEG",
                            subtitle = "Robust watermarks survive JPEG, so a smaller JPEG is fine. Off saves a lossless PNG.",
                            checked = s.outputJpeg,
                            onCheckedChange = { s.outputJpeg = it },
                        )
                        if (s.outputJpeg) {
                            Spacer(Modifier.height(8.dp))
                            Text("JPEG quality: ${s.jpegQuality}", fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = s.jpegQuality.toFloat(),
                                onValueChange = { s.jpegQuality = it.toInt() },
                                valueRange = 50f..100f,
                            )
                        }
                    }
                }
            }
            else -> {
                FilePickCard(
                    label = "Image to check",
                    chosen = s.markedUri?.let { displayName(context, it) },
                    hint = "The image you want to test for a watermark",
                    onPick = { pickMarked.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                FilePickCard(
                    label = "Signature file",
                    chosen = s.sigUri?.let { displayName(context, it) },
                    hint = "The .sig the image should carry",
                    onPick = { openSig.launch(arrayOf("*/*")) },
                )
                s.verdict?.let { WatermarkVerdictCard(it) }
            }
        }

        PrimaryActionButton(
            label = when (s.mode) {
                0 -> "Generate"
                1 -> "Embed"
                else -> "Verify"
            },
            busy = s.busy,
            onClick = {
                when (s.mode) {
                    0 -> runGenerate()
                    1 -> runEmbed()
                    else -> runVerify()
                }
            },
            // Generate is fast and reports nothing, so its bar stays indeterminate (progress = null).
            progress = if (s.mode == 0) null else s.progress,
            startedAtMs = s.startedAtMs,
        )

        if (s.mode != 2) {
            s.result?.let { r ->
                OutputResultCard(
                    name = r.name,
                    onSave = { saveOutput.launch(r.name) },
                    onShare = { shareResult() },
                )
            }
        }
    }
}
