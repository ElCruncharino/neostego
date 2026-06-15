/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.jpeguniward;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the correctness of {@link UniwardCost}'s fast separable cost against an independent
 * brute-force reference that builds the actual spatial perturbation for each DCT mode, filters it
 * with the full 2D directional wavelet, and accumulates the relative distortion directly.
 * <p>
 * The fast path exploits the fact that both the directional wavelet (an outer product of 1D db8
 * filters) and the spatial footprint of a unit DCT change (an outer product of two 8-point DCT basis
 * vectors) are separable, so the induced wavelet impact factors into a row response times a column
 * response. The brute force makes no such assumption &mdash; it convolves the genuine 2D delta. The
 * two must agree on <em>interior</em> blocks (edge blocks legitimately differ because the grid-edge
 * replicate padding clips the response; that only perturbs security, never recoverability).
 */
public class UniwardCostTest {

    /** db8 high-pass decomposition filter (16 taps) &mdash; must match {@link UniwardCost}. */
    private static final double[] HPDF = {
            -0.0544158422, 0.3128715909, -0.6756307363, 0.5853546837,
            0.0158291053, -0.2840155430, -0.0004724846, 0.1287474266,
            0.0173693010, -0.0440882539, -0.0139810279, 0.0087460940,
            0.0048703530, -0.0003917404, -0.0006754494, -0.0001174768
    };
    private static final int LF = HPDF.length; // 16
    private static final int CENTER = LF / 2;  // 8
    private static final double SIGMA = 1.0 / 64.0;

    private static double[] lpdf() {
        double[] l = new double[LF];
        for (int k = 0; k < LF; k++) {
            l[k] = ((k & 1) == 0 ? -1.0 : 1.0) * HPDF[LF - 1 - k];
        }
        return l;
    }

    private static double[][] dctMatrix() {
        double[][] a = new double[8][8];
        double c0 = Math.sqrt(1.0 / 8.0);
        double c = Math.sqrt(2.0 / 8.0);
        for (int u = 0; u < 8; u++) {
            for (int n = 0; n < 8; n++) {
                a[u][n] = (u == 0) ? c0 : c * Math.cos((2 * n + 1) * u * Math.PI / 16.0);
            }
        }
        return a;
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /**
     * Full 2D centered correlation with replicate padding: {@code out[m][n] = sum_a sum_b
     * rowFilter[a]*colFilter[b]*grid[m+a-CENTER][n+b-CENTER]}. Separable two-pass, identical
     * convention to the production residual filtering, but applied to an arbitrary grid (cover or
     * delta) so the cost reconstruction is independent of the separable shortcut.
     */
    private static double[][] correlate(double[][] grid, int gh, int gw, double[] rowFilter, double[] colFilter) {
        double[][] tmp = new double[gh][gw];
        for (int m = 0; m < gh; m++) {
            for (int n = 0; n < gw; n++) {
                double acc = 0.0;
                for (int b = 0; b < LF; b++) {
                    acc += colFilter[b] * grid[m][clamp(n + b - CENTER, gw)];
                }
                tmp[m][n] = acc;
            }
        }
        double[][] out = new double[gh][gw];
        for (int m = 0; m < gh; m++) {
            for (int n = 0; n < gw; n++) {
                double acc = 0.0;
                for (int a = 0; a < LF; a++) {
                    acc += rowFilter[a] * tmp[clamp(m + a - CENTER, gh)][n];
                }
                out[m][n] = acc;
            }
        }
        return out;
    }

    @Test
    public void interiorBlocksMatchBruteForce() {
        int blocksWide = 3;
        int blocksHigh = 3;
        int gh = blocksHigh * 8; // 24
        int gw = blocksWide * 8; // 24

        Random rnd = new Random(20260615L);
        double[][] plane = new double[gh][gw];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                plane[y][x] = rnd.nextInt(256);
            }
        }

        int[] quant = new int[64];
        for (int k = 0; k < 64; k++) {
            quant[k] = 8 + k; // arbitrary positive table; cost is linear in quant[k]
        }

        double[][] fast = UniwardCost.compute(plane, gh, gw, blocksWide, blocksHigh, quant);

        double[] lp = lpdf();
        double[][] a = dctMatrix();

        // Cover residual inverse denominators for the three directional subbands.
        double[][] inv1 = inverse(correlate(plane, gh, gw, lp, HPDF));
        double[][] inv2 = inverse(correlate(plane, gh, gw, HPDF, lp));
        double[][] inv3 = inverse(correlate(plane, gh, gw, HPDF, HPDF));

        // Center block (1,1) is fully interior: its 8-sample support, widened by the 16-tap filter,
        // spans rows/cols 1..23, all inside the 24x24 grid, so no padding is involved.
        int br = 1;
        int bc = 1;
        int r0 = br * 8;
        int c0 = bc * 8;
        double[] fastBlock = fast[br * blocksWide + bc];

        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                // Spatial footprint of a +1 change to quantized coeff (i,j): quant_ij * outer(A[i], A[j]).
                double[][] delta = new double[gh][gw];
                double q = quant[i * 8 + j];
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        delta[r0 + y][c0 + x] = q * a[i][y] * a[j][x];
                    }
                }
                double[][] dW1 = correlate(delta, gh, gw, lp, HPDF);
                double[][] dW2 = correlate(delta, gh, gw, HPDF, lp);
                double[][] dW3 = correlate(delta, gh, gw, HPDF, HPDF);

                double rho = 0.0;
                for (int m = 0; m < gh; m++) {
                    for (int n = 0; n < gw; n++) {
                        rho += Math.abs(dW1[m][n]) * inv1[m][n];
                        rho += Math.abs(dW2[m][n]) * inv2[m][n];
                        rho += Math.abs(dW3[m][n]) * inv3[m][n];
                    }
                }

                double got = fastBlock[i * 8 + j];
                assertEquals(rho, got, 1e-6 * (1.0 + Math.abs(rho)),
                        "mode (" + i + "," + j + ") cost mismatch");
                assertTrue(got > 0.0, "AC cost must be positive for mode (" + i + "," + j + ")");
            }
        }

        // DC must be left non-embeddable.
        assertEquals(0.0, fastBlock[0], 0.0, "DC cost must be zero");
    }

    private static double[][] inverse(double[][] w) {
        int h = w.length;
        int wd = w[0].length;
        double[][] inv = new double[h][wd];
        for (int m = 0; m < h; m++) {
            for (int n = 0; n < wd; n++) {
                inv[m][n] = 1.0 / (Math.abs(w[m][n]) + SIGMA);
            }
        }
        return inv;
    }
}
