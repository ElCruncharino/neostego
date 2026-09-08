/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego

import com.openstego.desktop.plugin.jpeguniward.UniwardCostAccelerator as CoreAccelerator

/**
 * NEON-backed [CoreAccelerator.Impl], registered in [NeoStegoApp] when the native library loads.
 * Flattens the row-major `double[][]` plane for the JNI call (a single contiguous array is far
 * cheaper to marshal than one JNI array per row) and unflattens the returned per-block cost rows.
 */
internal object UniwardCostAccelerator : CoreAccelerator.Impl {

    /** True once `libneostego_uniward.so` has loaded; false forever after any load failure. */
    val available: Boolean = try {
        System.loadLibrary("neostego_uniward")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    override fun compute(
        plane: Array<DoubleArray>,
        planeH: Int,
        planeW: Int,
        blocksWide: Int,
        blocksHigh: Int,
        quant: IntArray,
    ): Array<DoubleArray>? {
        if (!available) return null
        val flat = DoubleArray(planeH * planeW)
        for (r in 0 until planeH) {
            System.arraycopy(plane[r], 0, flat, r * planeW, planeW)
        }
        val costFlat = computeFlatNative(flat, planeH, planeW, blocksWide, blocksHigh, quant)
        val numBlocks = blocksWide * blocksHigh
        return Array(numBlocks) { i -> costFlat.copyOfRange(i * 64, i * 64 + 64) }
    }

    private external fun computeFlatNative(
        planeFlat: DoubleArray,
        planeH: Int,
        planeW: Int,
        blocksWide: Int,
        blocksHigh: Int,
        quant: IntArray,
    ): DoubleArray
}
