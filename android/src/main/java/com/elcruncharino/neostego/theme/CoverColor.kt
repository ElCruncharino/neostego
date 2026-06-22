/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a representative seed colour (ARGB) from a cover image for the "album-art" theming, or
 * null if it can't be read. Downsampling is fine here — we want the image's overall colour, not its
 * least-significant bits — so the bitmap is decoded small and handed to AndroidX [Palette]. Prefers a
 * vibrant swatch, then muted, then the dominant colour.
 */
suspend fun extractSeedColor(context: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
    val bitmap = decodeDownsampled(context, uri, target = 256) ?: return@withContext null
    try {
        val palette = Palette.from(bitmap).generate()
        palette.getVibrantColor(0).takeIf { it != 0 }
            ?: palette.getMutedColor(0).takeIf { it != 0 }
            ?: palette.getDominantColor(0).takeIf { it != 0 }
    } finally {
        bitmap.recycle()
    }
}

/** Decodes [uri] downsampled so its longer edge is roughly [target] pixels. */
private fun decodeDownsampled(context: Context, uri: Uri, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longer = maxOf(bounds.outWidth, bounds.outHeight)
    if (longer <= 0) return null
    var sample = 1
    while (longer / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}
