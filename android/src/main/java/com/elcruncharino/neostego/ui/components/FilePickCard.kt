/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.R

@Composable
fun FilePickCard(label: String, chosen: String?, hint: String, onPick: () -> Unit) {
    val pickButtonLabel = if (chosen == null) stringResource(R.string.btn_choose) else stringResource(R.string.btn_change)
    val pickButtonDescription = if (chosen == null) {
        stringResource(R.string.cd_choose_item, label)
    } else {
        stringResource(R.string.cd_change_item, label)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                // Merge the label + current selection so TalkBack announces the card as one unit
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label. ${chosen ?: hint}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    chosen ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.semantics { contentDescription = pickButtonDescription },
            ) { Text(pickButtonLabel) }
        }
    }
}
