/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.jpeg;

/**
 * Forward 8&times;8 type-II DCT for baseline JPEG, in the orthonormal scaling that matches the
 * ITU-T T.81 transform (so dividing the output by the standard quantization table reproduces JPEG
 * quantized coefficients). Implemented directly from the spec's separable definition &mdash; no
 * F5-lineage code.
 * <p>
 * The forward transform is the codec's main path (pixels&nbsp;&rarr;&nbsp;coefficients for a
 * precover); edits are made on the quantized coefficients and entropy-coded back verbatim. The
 * {@link #inverse} transform is provided only for <em>plain</em> J-UNIWARD on an already-compressed
 * JPEG cover, where the UNIWARD cost must be computed on the decompressed sample plane rebuilt from
 * the (dequantized) coefficients &mdash; there is no precover to read the samples from.
 */
final class Dct8x8 {

    /** Cosine basis matrix C such that {@code F = C * (block-128) * C^T}. */
    private static final double[][] C = new double[8][8];
    /** Transpose of {@link #C}. */
    private static final double[][] CT = new double[8][8];

    static {
        for (int j = 0; j < 8; j++) {
            C[0][j] = 1.0 / Math.sqrt(8.0);
            CT[j][0] = C[0][j];
        }
        for (int i = 1; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                C[i][j] = Math.sqrt(2.0 / 8.0) * Math.cos(((2 * j + 1) * i * Math.PI) / 16.0);
                CT[j][i] = C[i][j];
            }
        }
    }

    private Dct8x8() {
        // Static utility
    }

    /**
     * Applies the forward DCT to one 8&times;8 sample block. Input samples are level-shifted by
     * &minus;128 (per T.81) before the transform.
     *
     * @param block  64 spatial samples in row-major order, each 0..255
     * @param output 64 transform coefficients in row-major order (natural, not zig-zag)
     */
    static void forward(double[] block, double[] output) {
        // tmp = (block - 128) * C^T  ->  then F = C * tmp
        double[] tmp = new double[64];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                double sum = 0.0;
                for (int k = 0; k < 8; k++) {
                    sum += (block[i * 8 + k] - 128.0) * CT[k][j];
                }
                tmp[i * 8 + j] = sum;
            }
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                double sum = 0.0;
                for (int k = 0; k < 8; k++) {
                    sum += C[i][k] * tmp[k * 8 + j];
                }
                output[i * 8 + j] = sum;
            }
        }
    }

    /**
     * Applies the inverse DCT to one 8&times;8 block of (dequantized) coefficients, reconstructing the
     * spatial samples. Since {@code C} is orthonormal, {@code C^{-1} = C^T}, so inverting
     * {@code F = C(block-128)C^T} gives {@code block = C^T F C + 128}. The result is level-shifted back
     * by &plus;128, rounded to the nearest integer and clamped to {@code 0..255}, matching what a
     * baseline JPEG decoder produces.
     *
     * @param coeff  64 dequantized transform coefficients in row-major (natural) order
     * @param output 64 reconstructed spatial samples (0..255) in row-major order
     */
    static void inverse(double[] coeff, double[] output) {
        // tmp = C^T * F  ->  then block = tmp * C
        double[] tmp = new double[64];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                double sum = 0.0;
                for (int k = 0; k < 8; k++) {
                    sum += CT[i][k] * coeff[k * 8 + j];
                }
                tmp[i * 8 + j] = sum;
            }
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                double sum = 0.0;
                for (int k = 0; k < 8; k++) {
                    sum += tmp[i * 8 + k] * C[k][j];
                }
                long v = Math.round(sum + 128.0);
                output[i * 8 + j] = v < 0 ? 0 : (v > 255 ? 255 : v);
            }
        }
    }
}
