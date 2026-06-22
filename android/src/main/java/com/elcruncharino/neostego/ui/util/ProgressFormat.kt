/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import kotlin.math.roundToInt

/**
 * Formats a remaining-time estimate (in milliseconds) as a short label such as "~12s" or "~1m 5s".
 */
fun formatEta(remainingMs: Long): String {
    val secs = (if (remainingMs < 0) 0 else remainingMs) / 1000
    if (secs < 60) {
        return "~${secs}s"
    }
    return "~${secs / 60}m ${secs % 60}s"
}

/**
 * Builds the "Working…" label for the primary action button. When [progress] is known it appends a
 * percentage, and once enough progress has elapsed to extrapolate, a time estimate derived from
 * [startedAtMs] (system-clock milliseconds at the start of the operation) and [nowMs].
 */
fun workingLabel(progress: Float?, startedAtMs: Long, nowMs: Long): String {
    if (progress == null) {
        return "Working…"
    }
    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
    // Skip the ETA until a few percent in (early estimates are wild) and once effectively done.
    if (progress <= 0.05f || progress >= 0.99f || startedAtMs <= 0L) {
        return "Working… $pct%"
    }
    val elapsed = nowMs - startedAtMs
    val remaining = (elapsed * (1f - progress) / progress).toLong()
    return "Working… $pct% · ${formatEta(remaining)}"
}
