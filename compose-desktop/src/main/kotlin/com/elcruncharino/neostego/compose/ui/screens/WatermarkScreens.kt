/*
 * The three digital-watermarking screens — Generate signature, Embed watermark, Verify watermark —
 * ported from the Swing UI and wired to :core (DWTSVD / DWTDugad).
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import com.elcruncharino.neostego.compose.engine.Verdict
import com.elcruncharino.neostego.compose.engine.VerdictLevel
import com.elcruncharino.neostego.compose.engine.embedWatermark
import com.elcruncharino.neostego.compose.engine.generateSignature
import com.elcruncharino.neostego.compose.engine.pickFile
import com.elcruncharino.neostego.compose.engine.verifyWatermark
import com.elcruncharino.neostego.compose.theme.verdictColors
import com.elcruncharino.neostego.compose.ui.AlgorithmSelector
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SecurePasswordField
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.action_embed_watermark
import openstego.compose_desktop.generated.resources.action_generate_signature
import openstego.compose_desktop.generated.resources.action_verify_watermark
import openstego.compose_desktop.generated.resources.allowed_extensions_hint
import openstego.compose_desktop.generated.resources.cover_file_label
import openstego.compose_desktop.generated.resources.cover_file_watermark_hint_generic
import openstego.compose_desktop.generated.resources.output_file_label
import openstego.compose_desktop.generated.resources.output_watermarked_hint_generic
import openstego.compose_desktop.generated.resources.result_saved_signature_to
import openstego.compose_desktop.generated.resources.result_wrote_watermarked_file_to
import openstego.compose_desktop.generated.resources.saved_as_hint
import openstego.compose_desktop.generated.resources.section_key
import openstego.compose_desktop.generated.resources.signature_file_embed_hint
import openstego.compose_desktop.generated.resources.signature_file_label
import openstego.compose_desktop.generated.resources.signature_file_original_hint
import openstego.compose_desktop.generated.resources.signature_file_save_hint
import openstego.compose_desktop.generated.resources.verdict_correlation
import openstego.compose_desktop.generated.resources.verdict_failed_title
import openstego.compose_desktop.generated.resources.verdict_verification_failed
import openstego.compose_desktop.generated.resources.verdict_watermark_absent
import openstego.compose_desktop.generated.resources.verdict_watermark_present
import openstego.compose_desktop.generated.resources.verdict_weak_match
import openstego.compose_desktop.generated.resources.watermark_embed_intro
import openstego.compose_desktop.generated.resources.watermark_generate_intro
import openstego.compose_desktop.generated.resources.watermark_verify_intro
import openstego.compose_desktop.generated.resources.watermarked_file_check_hint_generic
import openstego.compose_desktop.generated.resources.watermarked_file_label
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val SIG = listOf("sig")

@Composable
fun GenerateSignatureScreen(algorithms: List<AlgoInfo>) {
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull()) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var sigFile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    val savedSignatureToTemplate = stringResource(Res.string.result_saved_signature_to)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ScreenIntro(stringResource(Res.string.watermark_generate_intro))
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        // The key seeds the watermark, exactly like a password does for embed/verify (which already
        // mask their input) - masking it here too, matching Android's SecurePasswordField usage.
        val keyLabel = stringResource(Res.string.section_key)
        SecurePasswordField(
            value = key,
            onValueChange = { key = it },
            show = showKey,
            onToggleShow = { showKey = !showKey },
            label = keyLabel,
        )

        FilePickCard(stringResource(Res.string.signature_file_label), sigFile, stringResource(Res.string.signature_file_save_hint)) {
            pickFile(save = true, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }

        PrimaryActionButton(stringResource(Res.string.action_generate_signature), busy = busy, onClick = {
            busy = true
            result = null
            Thread {
                result = runCatching { generateSignature(algorithm?.name.orEmpty(), key, sigFile.orEmpty()) }
                busy = false
            }.start()
        })
        result?.let { ResultCard(it) { path -> savedSignatureToTemplate.format(path) } }
    }
}

@Composable
fun EmbedWatermarkScreen(algorithms: List<AlgoInfo>) {
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull()) }
    var sigFile by remember { mutableStateOf<String?>(null) }
    var coverFile by remember { mutableStateOf<String?>(null) }
    var outputFile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    val wroteWatermarkedFileToTemplate = stringResource(Res.string.result_wrote_watermarked_file_to)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ScreenIntro(stringResource(Res.string.watermark_embed_intro))
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        val coverExts = algorithm?.coverExtensions.orEmpty()
        val stegoExts = algorithm?.stegoExtensions.orEmpty()
        FilePickCard(stringResource(Res.string.signature_file_label), sigFile, stringResource(Res.string.signature_file_embed_hint)) {
            pickFile(save = false, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }
        FilePickCard(
            stringResource(Res.string.cover_file_label),
            coverFile,
            if (coverExts.isEmpty()) stringResource(Res.string.cover_file_watermark_hint_generic) else stringResource(Res.string.allowed_extensions_hint, coverExts.joinToString(", ")),
        ) {
            pickFile(save = false, extensions = coverExts, filterLabel = "Cover files")?.let { coverFile = it }
        }
        FilePickCard(
            stringResource(Res.string.output_file_label),
            outputFile,
            if (stegoExts.isEmpty()) stringResource(Res.string.output_watermarked_hint_generic) else stringResource(Res.string.saved_as_hint, stegoExts.joinToString(", ")),
        ) {
            pickFile(save = true, extensions = stegoExts, filterLabel = "Watermarked")?.let { outputFile = it }
        }

        PrimaryActionButton(stringResource(Res.string.action_embed_watermark), busy = busy, onClick = {
            busy = true
            result = null
            Thread {
                result = runCatching {
                    embedWatermark(algorithm?.name.orEmpty(), sigFile.orEmpty(), coverFile.orEmpty(), outputFile.orEmpty())
                }
                busy = false
            }.start()
        })
        result?.let { ResultCard(it) { path -> wroteWatermarkedFileToTemplate.format(path) } }
    }
}

@Composable
fun VerifyWatermarkScreen(algorithms: List<AlgoInfo>) {
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull()) }
    var watermarkedFile by remember { mutableStateOf<String?>(null) }
    var sigFile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Result<Verdict>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ScreenIntro(stringResource(Res.string.watermark_verify_intro))
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        val exts = algorithm?.coverExtensions.orEmpty()
        FilePickCard(
            stringResource(Res.string.watermarked_file_label),
            watermarkedFile,
            if (exts.isEmpty()) stringResource(Res.string.watermarked_file_check_hint_generic) else stringResource(Res.string.allowed_extensions_hint, exts.joinToString(", ")),
        ) {
            pickFile(save = false, extensions = exts, filterLabel = "Image files")?.let { watermarkedFile = it }
        }
        FilePickCard(stringResource(Res.string.signature_file_label), sigFile, stringResource(Res.string.signature_file_original_hint)) {
            pickFile(save = false, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }

        PrimaryActionButton(stringResource(Res.string.action_verify_watermark), busy = busy, onClick = {
            busy = true
            verdict = null
            Thread {
                verdict = runCatching {
                    verifyWatermark(algorithm?.name.orEmpty(), watermarkedFile.orEmpty(), sigFile.orEmpty())
                }
                busy = false
            }.start()
        })
        verdict?.let { VerdictCard(it) }
    }
}

@Composable
private fun ScreenIntro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VerdictCard(result: Result<Verdict>) {
    val v = result.getOrNull()
    if (v != null) {
        val sc = verdictColors(v.level)
        val pct = stringResource(Res.string.verdict_correlation, (v.correlation * 100).roundToInt())
        // Icon + text + contrast-safe colour so the verdict never depends on colour alone (WCAG 1.4.1).
        val (icon, title) = when (v.level) {
            VerdictLevel.PRESENT -> Icons.Filled.CheckCircle to stringResource(Res.string.verdict_watermark_present)
            VerdictLevel.WEAK -> Icons.Filled.Warning to stringResource(Res.string.verdict_weak_match)
            VerdictLevel.ABSENT -> Icons.Filled.Cancel to stringResource(Res.string.verdict_watermark_absent)
        }
        VerdictBox(icon, sc.container, sc.content, title, pct)
    } else {
        val sc = verdictColors(VerdictLevel.ABSENT)
        VerdictBox(
            Icons.Filled.Error,
            sc.container,
            sc.content,
            stringResource(Res.string.verdict_failed_title),
            result.exceptionOrNull()?.message ?: stringResource(Res.string.verdict_verification_failed),
        )
    }
}

@Composable
private fun VerdictBox(icon: ImageVector, container: Color, content: Color, title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content) // title text conveys meaning to SRs
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = content)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}
