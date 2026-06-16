/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util.svd;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for the one-sided Jacobi {@link Svd}. The properties that matter for watermarking are: faithful
 * reconstruction ({@code A == U diag(S) V^T}), singular values sorted descending and matching the spectrum, and that
 * perturbing {@code S[0]} and reconstructing yields a matrix whose recomputed largest singular value equals the target
 * (so a QIM-quantized {@code S[0]} is recoverable by a blind decoder).
 */
class SvdTest {

    private static final double TOL = 1e-6;

    @Test
    void reconstructsRandomMatrices() {
        Random rnd = new Random(99);
        for (int trial = 0; trial < 2000; trial++) {
            int n = 2 + rnd.nextInt(7); // 2..8
            double[][] a = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    a[i][j] = rnd.nextGaussian() * 100.0;
                }
            }
            Svd svd = new Svd(a);
            double[][] r = svd.reconstruct();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    assertEquals(a[i][j], r[i][j], 1e-4, "reconstruct mismatch");
                }
            }
            // Singular values sorted descending and non-negative.
            double[] s = svd.getSingularValues();
            for (int i = 1; i < n; i++) {
                assertTrue(s[i] <= s[i - 1] + TOL, "not sorted descending");
                assertTrue(s[i] >= -TOL, "negative singular value");
            }
            // Sum of squares of singular values equals Frobenius norm squared.
            double fro = 0.0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    fro += a[i][j] * a[i][j];
                }
            }
            double sumSq = 0.0;
            for (double v : s) {
                sumSq += v * v;
            }
            assertEquals(fro, sumSq, 1e-3 * (fro + 1.0), "energy mismatch");
        }
    }

    @Test
    void diagonalSpectrum() {
        double[][] a = {{5, 0, 0}, {0, 2, 0}, {0, 0, 9}};
        double[] s = new Svd(a).getSingularValues();
        assertEquals(9.0, s[0], TOL);
        assertEquals(5.0, s[1], TOL);
        assertEquals(2.0, s[2], TOL);
    }

    @Test
    void largestSingularValueIsRecoverableAfterEdit() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 1000; trial++) {
            int n = 8;
            double[][] a = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    a[i][j] = 128.0 + rnd.nextGaussian() * 30.0;
                }
            }
            Svd svd = new Svd(a);
            double target = svd.getSingularValue(0) + (rnd.nextDouble() - 0.5) * 40.0;
            svd.setSingularValue(0, target);
            double[][] edited = svd.reconstruct();

            // A blind decoder recomputes the SVD of the edited block; its largest singular value must equal target.
            double recovered = new Svd(edited).getSingularValue(0);
            assertEquals(target, recovered, 1e-3, "largest singular value not recoverable");
        }
    }
}
