/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A coarse map of which regions of a cover image were altered by embedding, produced by diffing the
 * cover against the produced stego image pixel-for-pixel and aggregating into display cells.
 *
 * [active] is row-major, length [cols] * [rows]; a cell is true when *any* pixel in the region it
 * covers differs between cover and stego. [blockBased] is set for JPEG covers, where data lives in
 * 8×8 DCT blocks, so the honest unit is a block/region rather than an individual pixel.
 */
data class EmbedMap(
    val cols: Int,
    val rows: Int,
    val active: BooleanArray,
    val changedCells: Int,
    val totalCells: Int,
    val blockBased: Boolean,
) {
    val fraction: Float get() = if (totalCells == 0) 0f else changedCells.toFloat() / totalCells
}

/** Target number of display cells along the longer image edge. */
private const val GRID_MAX = 56

/**
 * Computes an [EmbedMap] by decoding both images at full resolution (so single-bit changes are not
 * averaged away) and marking each display cell active if any covered pixel changed. Runs on the
 * default dispatcher; bitmaps are decoded and released sequentially to keep peak memory in check.
 * Returns null if either image can't be decoded or their dimensions disagree.
 */
suspend fun computeEmbedMap(
    coverBytes: ByteArray,
    stegoBytes: ByteArray,
    blockBased: Boolean,
): EmbedMap? = withContext(Dispatchers.Default) {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

    val coverBmp = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, opts) ?: return@withContext null
    val width = coverBmp.width
    val height = coverBmp.height
    if (width <= 0 || height <= 0) {
        coverBmp.recycle()
        return@withContext null
    }
    val coverPixels = IntArray(width * height)
    coverBmp.getPixels(coverPixels, 0, width, 0, 0, width, height)
    coverBmp.recycle()

    val stegoBmp = BitmapFactory.decodeByteArray(stegoBytes, 0, stegoBytes.size, opts) ?: return@withContext null
    if (stegoBmp.width != width || stegoBmp.height != height) {
        stegoBmp.recycle()
        return@withContext null
    }
    val stegoPixels = IntArray(width * height)
    stegoBmp.getPixels(stegoPixels, 0, width, 0, 0, width, height)
    stegoBmp.recycle()

    // Grid sized so the longer edge has up to GRID_MAX cells, keeping cells roughly square.
    val cols = if (width >= height) GRID_MAX else (GRID_MAX * width / height).coerceAtLeast(1)
    val rows = if (height >= width) GRID_MAX else (GRID_MAX * height / width).coerceAtLeast(1)
    val active = BooleanArray(cols * rows)

    var y = 0
    while (y < height) {
        val cellRow = (y.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
        val rowBase = y * width
        var x = 0
        while (x < width) {
            val idx = rowBase + x
            if (coverPixels[idx] != stegoPixels[idx]) {
                val cellCol = (x.toLong() * cols / width).toInt().coerceIn(0, cols - 1)
                active[cellRow * cols + cellCol] = true
            }
            x++
        }
        y++
    }

    var changed = 0
    for (cell in active) if (cell) changed++
    EmbedMap(cols, rows, active, changed, cols * rows, blockBased)
}
