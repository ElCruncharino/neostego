/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import com.elcruncharino.neostego.R

/** An image's pixel dimensions and detected format, read without decoding its pixels. */
internal data class ImageInfo(val width: Int, val height: Int, val isJpeg: Boolean)

/** Reads just the dimensions of an image without decoding its pixels. Returns 0 if unknown. */
internal fun imagePixelCount(context: Context, uri: Uri): Long {
    val info = imageInfo(context, uri) ?: return 0L
    return info.width.toLong() * info.height.toLong()
}

/**
 * Reads an image's pixel dimensions and real format (by content, not by file extension) without
 * decoding its pixels. Returns null if the file isn't a decodable image at all.
 */
internal fun imageInfo(context: Context, uri: Uri): ImageInfo? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    return ImageInfo(opts.outWidth, opts.outHeight, opts.outMimeType == "image/jpeg")
}

/** Formats a byte count as a short, already-localized human-readable string (e.g. "12 KB", "3.4 MB"). */
internal fun humanBytes(context: Context, bytes: Int): String = Formatter.formatShortFileSize(context, bytes.toLong())

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
    return context.getString(R.string.error_image_too_large_generic, megapixels)
}
