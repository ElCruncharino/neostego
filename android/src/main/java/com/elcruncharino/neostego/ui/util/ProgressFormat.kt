/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import android.content.Context
import com.elcruncharino.neostego.R
import kotlin.math.roundToInt

/**
 * Formats a remaining-time estimate (in milliseconds) as a short label such as "~12s" or "~1m 5s".
 */
fun formatEta(context: Context, remainingMs: Long): String {
    val secs = (if (remainingMs < 0) 0 else remainingMs) / 1000
    if (secs < 60) {
        return context.getString(R.string.eta_seconds, secs)
    }
    return context.getString(R.string.eta_minutes_seconds, secs / 60, secs % 60)
}

/**
 * Builds the "Working…" label for the primary action button. When [progress] is known it appends a
 * percentage, and once enough progress has elapsed to extrapolate, a time estimate derived from
 * [startedAtMs] (system-clock milliseconds at the start of the operation) and [nowMs].
 */
fun workingLabel(context: Context, progress: Float?, startedAtMs: Long, nowMs: Long): String {
    if (progress == null) {
        return context.getString(R.string.label_working)
    }
    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
    // Skip the ETA until a few percent in (early estimates are wild) and once effectively done.
    if (progress <= 0.05f || progress >= 0.99f || startedAtMs <= 0L) {
        return context.getString(R.string.label_working_pct, pct)
    }
    val elapsed = nowMs - startedAtMs
    val remaining = (elapsed * (1f - progress) / progress).toLong()
    return context.getString(R.string.label_working_pct_eta, pct, formatEta(context, remaining))
}
