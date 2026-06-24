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
import androidx.compose.material3.OutlinedTextField
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
import com.elcruncharino.neostego.compose.ui.AlgorithmSelector
import com.elcruncharino.neostego.compose.ui.FilePickCard
import com.elcruncharino.neostego.compose.ui.PrimaryActionButton
import com.elcruncharino.neostego.compose.ui.ResultCard
import com.elcruncharino.neostego.compose.ui.SectionLabel
import kotlin.math.roundToInt

private val SIG = listOf("sig")

@Composable
fun GenerateSignatureScreen(algorithms: List<AlgoInfo>) {
    var algorithm by remember { mutableStateOf(algorithms.firstOrNull()) }
    var key by remember { mutableStateOf("") }
    var sigFile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ScreenIntro("Create a watermark signature file from a secret key.")
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        SectionLabel("Key")
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FilePickCard("Signature file", sigFile, "Where to save the .sig file") {
            pickFile(save = true, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }

        PrimaryActionButton("Generate signature", busy = busy, onClick = {
            busy = true
            result = null
            Thread {
                result = runCatching { generateSignature(algorithm?.name.orEmpty(), key, sigFile.orEmpty()) }
                busy = false
            }.start()
        })
        result?.let { ResultCard(it) { path -> "Saved signature to $path" } }
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ScreenIntro("Embed a signature into a cover file as an invisible watermark.")
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        val coverExts = algorithm?.coverExtensions.orEmpty()
        val stegoExts = algorithm?.stegoExtensions.orEmpty()
        FilePickCard("Signature file", sigFile, "The .sig to embed") {
            pickFile(save = false, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }
        FilePickCard(
            "Cover file",
            coverFile,
            if (coverExts.isEmpty()) "The file to watermark" else "Allowed: ${coverExts.joinToString(", ")}",
        ) {
            pickFile(save = false, extensions = coverExts, filterLabel = "Cover files")?.let { coverFile = it }
        }
        FilePickCard(
            "Output file",
            outputFile,
            if (stegoExts.isEmpty()) "Where to save the watermarked file" else "Saved as: ${stegoExts.joinToString(", ")}",
        ) {
            pickFile(save = true, extensions = stegoExts, filterLabel = "Watermarked")?.let { outputFile = it }
        }

        PrimaryActionButton("Embed watermark", busy = busy, onClick = {
            busy = true
            result = null
            Thread {
                result = runCatching {
                    embedWatermark(algorithm?.name.orEmpty(), sigFile.orEmpty(), coverFile.orEmpty(), outputFile.orEmpty())
                }
                busy = false
            }.start()
        })
        result?.let { ResultCard(it) { path -> "Wrote watermarked file to $path" } }
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
        ScreenIntro("Check whether a file carries a given watermark signature.")
        AlgorithmSelector(algorithms, algorithm) { algorithm = it }

        val exts = algorithm?.coverExtensions.orEmpty()
        FilePickCard(
            "Watermarked file",
            watermarkedFile,
            if (exts.isEmpty()) "The file to check" else "Allowed: ${exts.joinToString(", ")}",
        ) {
            pickFile(save = false, extensions = exts, filterLabel = "Image files")?.let { watermarkedFile = it }
        }
        FilePickCard("Signature file", sigFile, "The original .sig") {
            pickFile(save = false, extensions = SIG, filterLabel = "Signature")?.let { sigFile = it }
        }

        PrimaryActionButton("Verify watermark", busy = busy, onClick = {
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
    val scheme = MaterialTheme.colorScheme
    val v = result.getOrNull()
    if (v != null) {
        val pct = "Correlation: ${(v.correlation * 100).roundToInt()}%"
        // Icon + text label so the verdict never depends on colour alone (WCAG 1.4.1).
        when (v.level) {
            VerdictLevel.PRESENT ->
                VerdictBox(Icons.Filled.CheckCircle, scheme.secondaryContainer, scheme.onSecondaryContainer, "Watermark present", pct)
            VerdictLevel.WEAK ->
                VerdictBox(Icons.Filled.Warning, scheme.tertiaryContainer, scheme.onTertiaryContainer, "Weak / partial match", pct)
            VerdictLevel.ABSENT ->
                VerdictBox(Icons.Filled.Cancel, scheme.errorContainer, scheme.onErrorContainer, "Watermark absent", pct)
        }
    } else {
        VerdictBox(
            Icons.Filled.Error,
            scheme.errorContainer,
            scheme.onErrorContainer,
            "Failed",
            result.exceptionOrNull()?.message ?: "Verification failed",
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
