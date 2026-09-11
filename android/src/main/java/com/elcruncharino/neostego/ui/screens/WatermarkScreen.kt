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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.R
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

    val toastSavedFileTemplate = stringResource(R.string.toast_saved_file)
    val errorSavingTemplate = stringResource(R.string.error_saving)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val errorSharingTemplate = stringResource(R.string.error_sharing)
    val errorEnterPasswordSignature = stringResource(R.string.error_enter_password_signature)
    val errorFailedGenerateSignature = stringResource(R.string.error_failed_generate_signature)
    val errorChooseImageWatermark = stringResource(R.string.error_choose_image_watermark)
    val errorChooseSignatureFile = stringResource(R.string.error_choose_signature_file)
    val errorImageTooLargeWatermark = stringResource(R.string.error_image_too_large_watermark)
    val errorFailedEmbedWatermark = stringResource(R.string.error_failed_embed_watermark)
    val errorChooseImageCheck = stringResource(R.string.error_choose_image_check)
    val errorImageTooLargeCheck = stringResource(R.string.error_image_too_large_check)
    val errorFailedVerifyWatermark = stringResource(R.string.error_failed_verify_watermark)

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

    LaunchedEffect(s.mode) {
        s.verdict = null
        setResult(null)
    }

    fun runGenerate() {
        val pw = readPasswordChars(s.passwordView)
        if (pw == null) {
            toast(errorEnterPasswordSignature)
            return
        }
        s.busy = true
        scope.launch {
            try {
                val sig = withContext(Dispatchers.IO) { StegoEngine.generateSignature(s.algo, pw) }
                setResult(OutputResult("watermark.sig", "application/octet-stream", sig))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: errorFailedGenerateSignature)
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
            toast(errorChooseImageWatermark)
            return
        }
        if (sig == null) {
            toast(errorChooseSignatureFile)
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
                snackbar.showSnackbar(errorImageTooLargeWatermark)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: errorFailedEmbedWatermark)
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
            toast(errorChooseImageCheck)
            return
        }
        if (sig == null) {
            toast(errorChooseSignatureFile)
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
                snackbar.showSnackbar(errorImageTooLargeCheck)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: errorFailedVerifyWatermark)
            } finally {
                s.busy = false
                s.progress = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SegmentedButtonGroup(
            options = listOf(
                stringResource(R.string.watermark_mode_generate),
                stringResource(R.string.watermark_mode_embed),
                stringResource(R.string.watermark_mode_verify),
            ),
            selectedIndex = s.mode,
            onSelect = { s.mode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            when (s.mode) {
                0 -> stringResource(R.string.watermark_desc_generate)
                1 -> stringResource(R.string.watermark_desc_embed)
                else -> stringResource(R.string.watermark_desc_verify)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                Text(stringResource(R.string.label_algorithm), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.DWT_SVD,
                    title = stringResource(R.string.algo_dwt_svd_title),
                    subtitle = stringResource(R.string.algo_dwt_svd_subtitle),
                    onClick = { s.algo = StegoEngine.WmAlgorithm.DWT_SVD },
                )
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.DUGAD,
                    title = stringResource(R.string.algo_dwt_dugad_title),
                    subtitle = stringResource(R.string.algo_dwt_dugad_subtitle),
                    onClick = { s.algo = StegoEngine.WmAlgorithm.DUGAD },
                )
                AlgorithmOption(
                    selected = s.algo == StegoEngine.WmAlgorithm.XIE,
                    title = stringResource(R.string.algo_dwt_xie_title),
                    subtitle = stringResource(R.string.algo_dwt_xie_subtitle),
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
                    required = true,
                )
                Text(
                    stringResource(R.string.hint_signature_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            1 -> {
                FilePickCard(
                    label = stringResource(R.string.label_image_to_watermark),
                    chosen = s.coverUri?.let { displayName(context, it) },
                    hint = stringResource(R.string.hint_image_to_watermark),
                    onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                FilePickCard(
                    label = stringResource(R.string.label_signature_file),
                    chosen = s.sigUri?.let { displayName(context, it) },
                    hint = stringResource(R.string.hint_signature_file_generate),
                    onPick = { openSig.launch(arrayOf("*/*")) },
                )
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        ToggleRow(
                            title = stringResource(R.string.label_output_jpeg),
                            subtitle = stringResource(R.string.hint_output_jpeg),
                            checked = s.outputJpeg,
                            onCheckedChange = { s.outputJpeg = it },
                        )
                        if (s.outputJpeg) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.label_jpeg_quality, s.jpegQuality), fontWeight = FontWeight.SemiBold)
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
                    label = stringResource(R.string.label_image_to_check),
                    chosen = s.markedUri?.let { displayName(context, it) },
                    hint = stringResource(R.string.hint_image_to_check),
                    onPick = { pickMarked.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                FilePickCard(
                    label = stringResource(R.string.label_signature_file),
                    chosen = s.sigUri?.let { displayName(context, it) },
                    hint = stringResource(R.string.hint_signature_file_verify),
                    onPick = { openSig.launch(arrayOf("*/*")) },
                )
                s.verdict?.let { WatermarkVerdictCard(it) }
            }
        }

        PrimaryActionButton(
            label = when (s.mode) {
                0 -> stringResource(R.string.watermark_mode_generate)
                1 -> stringResource(R.string.watermark_mode_embed)
                else -> stringResource(R.string.watermark_mode_verify)
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
