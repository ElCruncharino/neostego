/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.image.jpeg.JpegImage;

/**
 * Uniform-embedding JPEG distortion cost, in the spirit of Guo/Ni/Shi's UERD (Uniform Embedding
 * Revisited Distortion): cheaper to compute than {@link UniwardCost} because it works directly from a
 * block's own quantized coefficients instead of a wavelet transform of the decompressed pixel plane, at
 * the cost of not modelling cross-block continuity the way UNIWARD's wavelet residual does. A
 * zero-valued coefficient is wet (near-infinite cost): flipping a zero AC coefficient to nonzero raises
 * the block's nonzero-coefficient count, a strong first-order steganalysis feature UNIWARD does not
 * need to special-case because its wavelet cost already penalizes it implicitly.
 * <p>
 * {@code rho(i,j) = q(i,j) / (blockEnergy * (i+j+1) + eps)}, where {@code blockEnergy} is the block's
 * total dequantized AC magnitude and {@code (i+j+1)} biases changes toward higher spatial frequencies.
 */
final class UerdCost {

    private static final double WET_COST = 1e10;
    private static final double EPS = 1e-6;

    private UerdCost() {}

    /** Same shape as {@link UniwardCost#compute}: one length-64 (DC-inclusive, unused) array per block. */
    static double[][] compute(JpegImage jpg, int comp, int r0, int r1, int blocksWide) {
        int[] quant = jpg.getQuantTable(comp);
        double[][] out = new double[(r1 - r0) * blocksWide][];
        for (int br = r0; br < r1; br++) {
            for (int bc = 0; bc < blocksWide; bc++) {
                short[] block = jpg.getBlock(comp, br, bc);
                double energy = 0.0;
                for (int k = 1; k < 64; k++) {
                    energy += Math.abs(quant[k] * block[k]);
                }
                double[] rho = new double[64];
                for (int k = 1; k < 64; k++) {
                    if (block[k] == 0) {
                        rho[k] = WET_COST;
                    } else {
                        int row = k / 8;
                        int col = k % 8;
                        rho[k] = quant[k] / (energy * (row + col + 1) + EPS);
                    }
                }
                out[(br - r0) * blocksWide + bc] = rho;
            }
        }
        return out;
    }
}
