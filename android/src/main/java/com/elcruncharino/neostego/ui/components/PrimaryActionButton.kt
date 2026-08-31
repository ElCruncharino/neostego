/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.ui.util.workingLabel
import kotlinx.coroutines.delay

/**
 * The full-width primary action button shared by Hide / Reveal / Watermark. While [busy] it shows a
 * thin progress bar above the button and a "Working…" label; when [progress] (0..1) is known the bar
 * is determinate and the label carries a percentage plus a time estimate derived from [startedAtMs].
 * When [progress] is null the bar is indeterminate (the operation does not report fine-grained
 * progress). Otherwise it shows the [label].
 */
@Composable
fun PrimaryActionButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    startedAtMs: Long = 0L,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
        Button(
            onClick = onClick,
            enabled = !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(12.dp))
                // Re-tick once a second so the ETA counts down between progress callbacks.
                val now by produceState(initialValue = System.currentTimeMillis(), busy, progress) {
                    while (true) {
                        value = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                Text(workingLabel(progress, startedAtMs, now))
            } else {
                Text(label)
            }
        }
    }
}
