/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Draws [coverImage] with an animated grid overlay that lights the cells in [active] (row-major,
 * [cols] × [rows]) in [accentColor], revealed by a left→right sweep. Used both for the truthful
 * cover-vs-stego embed map on Hide and the synthetic "scan" pattern on Reveal. The animation replays
 * whenever [replayKey] changes; its end state shows the full map, so it is purely decorative.
 */
@Composable
fun EmbedMapView(
    coverImage: ImageBitmap?,
    active: BooleanArray,
    cols: Int,
    rows: Int,
    accentColor: Color,
    replayKey: Any?,
    modifier: Modifier = Modifier,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(replayKey) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, animationSpec = tween(900))
    }

    val aspect = if (rows > 0) cols.toFloat() / rows else 1f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(20.dp)),
    ) {
        if (coverImage != null) {
            Image(
                bitmap = coverImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Darken the photo so lit cells read clearly.
            drawRect(color = Color.Black.copy(alpha = 0.40f))

            val cellW = size.width / cols
            val cellH = size.height / rows
            val sweepX = sweep.value * size.width
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val left = c * cellW
                    val centerX = left + cellW / 2f
                    if (centerX > sweepX) continue // not yet revealed
                    val isActive = active[r * cols + c]
                    // Brighten cells right at the leading edge for a glowing scan line.
                    val edge = 1f - (abs(centerX - sweepX) / (cellW * 3f)).coerceIn(0f, 1f)
                    val top = r * cellH
                    val pad = cellW * 0.12f
                    if (isActive) {
                        drawRect(
                            color = accentColor.copy(alpha = 0.55f + 0.45f * edge),
                            topLeft = Offset(left + pad, top + pad),
                            size = Size(cellW - 2 * pad, cellH - 2 * pad),
                        )
                    } else {
                        drawRect(
                            color = Color.White.copy(alpha = 0.04f + 0.10f * edge),
                            topLeft = Offset(left + pad, top + pad),
                            size = Size(cellW - 2 * pad, cellH - 2 * pad),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds a synthetic, sparse, deterministic activation pattern for the Reveal screen, where there is
 * no original cover to diff against. Clearly decorative — a scattered "scan" rather than a real map.
 */
fun syntheticScanPattern(cols: Int, rows: Int): BooleanArray {
    val active = BooleanArray(cols * rows)
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            // A fixed hash gives a stable, scattered ~30% fill with no RNG.
            active[r * cols + c] = ((r * 31 + c * 17 + r * c * 7) % 10) < 3
        }
    }
    return active
}
