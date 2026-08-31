/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/** Reads just the dimensions of an image without decoding its pixels. Returns 0 if unknown. */
internal fun imagePixelCount(context: Context, uri: Uri): Long {
    val (w, h) = imageDimensions(context, uri) ?: return 0L
    return w.toLong() * h.toLong()
}

/** Reads an image's pixel dimensions without decoding its pixels. Returns null if unknown. */
internal fun imageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
}

/** Formats a byte count as a short human-readable string (e.g. "12 KB", "3.4 MB"). */
internal fun humanBytes(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes bytes"
}

/**
 * Returns a warning only when an image genuinely cannot fit in the app's heap, otherwise null.
 *
 * The data-hiding algorithm spreads the payload across the whole image via a password-seeded
 * permutation, so the full pixel buffer must be resident. The real peak is the decoded ARGB_8888
 * bitmap (width*height*4) plus the compressed PNG produced on save — roughly 2.5x the raw pixels.
 */
internal fun oversizeWarning(context: Context, uri: Uri): String? {
    val pixels = imagePixelCount(context, uri)
    if (pixels <= 0) return null
    val estPeakBytes = pixels * 4L * 5L / 2L
    val heap = Runtime.getRuntime().maxMemory()
    if (estPeakBytes <= heap * 0.8) return null
    val megapixels = pixels / 1_000_000.0
    return "This image is extremely large (about %.0f megapixels) and is bigger than the memory available to the app. ".format(megapixels) +
        "Try a smaller image."
}
