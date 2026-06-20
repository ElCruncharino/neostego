/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The full-width primary action button shared by Hide / Reveal / Watermark. Shows the expressive
 * loading indicator and a "Working..." label while [busy]; otherwise the [label].
 */
@Composable
fun PrimaryActionButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) {
            ExpressiveLoadingIndicator(diameter = 20.dp, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(12.dp))
            Text("Working...")
        } else {
            Text(label)
        }
    }
}
