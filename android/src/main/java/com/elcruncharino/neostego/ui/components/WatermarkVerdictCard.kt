/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.theme.VerdictLevel
import com.elcruncharino.neostego.theme.verdictColors

/** Renders a watermark verification result with a colour-coded verdict. */
@Composable
fun WatermarkVerdictCard(verdict: StegoEngine.WmVerdict) {
    // Pair every verdict with a distinct icon as well as a colour, so the result is not conveyed
    // by colour alone (colour-blind / low-vision users) and reads correctly under TalkBack.
    val (label, level, icon) = when {
        verdict.present -> Triple("Watermark present", VerdictLevel.PRESENT, Icons.Filled.CheckCircle)
        verdict.weak -> Triple("Weak / uncertain watermark", VerdictLevel.WEAK, Icons.Filled.Warning)
        else -> Triple("No watermark detected", VerdictLevel.ABSENT, Icons.Filled.Cancel)
    }
    // Render the verdict on its own contrast-checked container (see verdictColors) rather than on the
    // dynamic surfaceVariant, so the AA contrast holds regardless of the Material You palette/theme.
    val status = verdictColors(level)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(status.container)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = status.content)
                Text(label, fontWeight = FontWeight.SemiBold, color = status.content)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Correlation %.2f (strong ≥ %.2f, weak ≥ %.2f)".format(verdict.correlation, verdict.high, verdict.low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
