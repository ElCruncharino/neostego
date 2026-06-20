/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

/**
 * The UNIWARD distortion cost for changing a single quantized DCT coefficient by &plusmn;1.
 * <p>
 * UNIWARD ("UNIversal WAvelet Relative Distortion") measures how much a change disturbs the image in
 * the wavelet domain, relative to the cover's own wavelet content, so changes are pushed into
 * textured/noisy regions and away from smooth ones. The cost of a unit change to coefficient
 * {@code (i,j)} of a block is the additive approximation
 *
 * <pre>
 *   rho = sum over 3 directional subbands k, over all wavelet positions p of
 *             |dW_k[p]| / (|W_k[p]| + sigma)
 * </pre>
 *
 * where {@code W_k} is the cover image filtered by the k-th directional wavelet filter, {@code dW_k}
 * is the change that the coefficient edit induces in that subband, and {@code sigma = 2^-6} is a
 * stabilizing constant. The directional filters are built from the 1D Daubechies-8 (db8) wavelet
 * decomposition pair: LH = lpdf&otimes;hpdf, HL = hpdf&otimes;lpdf, HH = hpdf&otimes;hpdf.
 * <p>
 * Because each 2D filter is an outer product and the spatial footprint of a unit DCT change is itself
 * separable (the outer product of two 8-point DCT basis vectors), the induced wavelet change
 * {@code dW_k} for mode {@code (i,j)} factors into a row response (the DCT basis row filtered by one
 * 1D wavelet) and a column response. We precompute those 1D responses once per quant table, so each
 * coefficient's cost is a 23&times;23 accumulation against the precomputed cover residual.
 * <p>
 * This is the genuine wavelet relative distortion, not a Laplacian/Gaussian shortcut: the shortcut
 * would forfeit the security that makes UNIWARD the consensus best practical scheme. Correctness of
 * the (subtle) filter alignment is pinned by a brute-force equivalence test that perturbs the plane,
 * re-filters, and compares the resulting distortion against this fast path on interior blocks.
 */
final class UniwardCost {

    /** Stabilizing constant in the denominator, {@code 2^-6}. */
    static final double SIGMA = 1.0 / 64.0;

    /** db8 high-pass decomposition filter (16 taps), as used by the UNIWARD reference. */
    private static final double[] HPDF = {
        -0.0544158422,
        0.3128715909,
        -0.6756307363,
        0.5853546837,
        0.0158291053,
        -0.2840155430,
        -0.0004724846,
        0.1287474266,
        0.0173693010,
        -0.0440882539,
        -0.0139810279,
        0.0087460940,
        0.0048703530,
        -0.0003917404,
        -0.0006754494,
        -0.0001174768
    };

    /** Filter length and derived padding/pattern geometry. */
    private static final int LF = HPDF.length; // 16

    private static final int CENTER = LF / 2; // 8
    private static final int PAT = 8 + LF - 1; // 23 (full 1D response of an 8-tap basis)
    private static final int OFF = LF - 1; // 15 : A-index = s' + a - OFF

    private UniwardCost() {
        // Static utility.
    }

    /**
     * Computes the per-coefficient UNIWARD cost for one component's plane.
     *
     * @param plane      spatial sample plane, {@code [row][col]}, in the component's own resolution
     * @param planeH     plane height (samples)
     * @param planeW     plane width (samples)
     * @param blocksWide blocks per row (may extend past the plane; edge samples are replicated)
     * @param blocksHigh rows of blocks
     * @param quant      the component's quantization table (natural order, 64 entries)
     * @return {@code cost[blockIndex][64]} with {@code blockIndex = br*blocksWide + bc}; the DC entry
     *         (index 0) is left at 0 and is not embeddable
     */
    static double[][] compute(double[][] plane, int planeH, int planeW, int blocksWide, int blocksHigh, int[] quant) {
        // Low-pass decomposition filter from db8 high-pass via the QMF relation. Only relative signs
        // matter (costs use absolute values), so a global sign is irrelevant.
        double[] lpdf = new double[LF];
        for (int k = 0; k < LF; k++) {
            lpdf[k] = ((k & 1) == 0 ? -1.0 : 1.0) * HPDF[LF - 1 - k];
        }

        // Orthonormal 8x8 DCT matrix A[u][n]; a unit change to quantized coeff (i,j) shifts the
        // spatial block by quant_ij * outer(A[i], A[j]).
        double[][] a = dctMatrix();

        // 1D wavelet responses of each DCT basis row: |sum_a filter[a] * A[u][s'+a-OFF]|.
        double[][] absRespH = response(a, HPDF); // [u][s'] using high-pass
        double[][] absRespL = response(a, lpdf); // [u][s'] using low-pass

        // Grid spatial extent (blocks may exceed the plane; replicate edge samples).
        int gh = blocksHigh * 8;
        int gw = blocksWide * 8;

        // Cover wavelet residuals, precomputed as inverse denominators 1/(|W_k| + sigma).
        // F1 = lpdf(rows) x hpdf(cols), F2 = hpdf(rows) x lpdf(cols), F3 = hpdf x hpdf.
        double[][] inv1 = invResidual(plane, planeH, planeW, gh, gw, lpdf, HPDF);
        double[][] inv2 = invResidual(plane, planeH, planeW, gh, gw, HPDF, lpdf);
        double[][] inv3 = invResidual(plane, planeH, planeW, gh, gw, HPDF, HPDF);

        double[][] cost = new double[blocksWide * blocksHigh][64];
        int nBlocks = blocksWide * blocksHigh;
        // Each block's cost reads only shared read-only arrays (absRespL/H, inv1/2/3, quant) and writes
        // its own disjoint cost[] row, so the block loop parallelizes with bit-identical output. Gate on
        // size so tiny images don't pay fork/join overhead; below the threshold run sequentially.
        if (nBlocks >= PARALLEL_BLOCK_THRESHOLD) {
            final double[][] absL = absRespL, absH = absRespH;
            final double[][] i1 = inv1, i2 = inv2, i3 = inv3;
            final int bw = blocksWide, ghF = gh, gwF = gw;
            java.util.stream.IntStream.range(0, nBlocks)
                    .parallel()
                    .forEach(idx -> computeBlock(
                            cost[idx], (idx / bw) * 8, (idx % bw) * 8, absL, absH, i1, i2, i3, quant, ghF, gwF));
        } else {
            for (int idx = 0; idx < nBlocks; idx++) {
                computeBlock(
                        cost[idx],
                        (idx / blocksWide) * 8,
                        (idx % blocksWide) * 8,
                        absRespL,
                        absRespH,
                        inv1,
                        inv2,
                        inv3,
                        quant,
                        gh,
                        gw);
            }
        }
        return cost;
    }

    /** Block counts at or above this fork into the common pool; smaller grids stay single-threaded. */
    private static final int PARALLEL_BLOCK_THRESHOLD = 256;

    /** Fills one block's 64-entry cost row for the block whose top-left grid sample is {@code (r0,c0)}. */
    private static void computeBlock(
            double[] block,
            int r0,
            int c0,
            double[][] absRespL,
            double[][] absRespH,
            double[][] inv1,
            double[][] inv2,
            double[][] inv3,
            int[] quant,
            int gh,
            int gw) {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (i == 0 && j == 0) {
                    continue; // DC is not embeddable
                }
                double q = quant[i * 8 + j];
                double rho = accumulate(absRespL[i], absRespH[j], inv1, r0, c0, gh, gw)
                        + accumulate(absRespH[i], absRespL[j], inv2, r0, c0, gh, gw)
                        + accumulate(absRespH[i], absRespH[j], inv3, r0, c0, gh, gw);
                block[i * 8 + j] = q * rho;
            }
        }
    }

    /**
     * Accumulates one directional subband's contribution for a block/mode:
     * {@code sum_{s',t'} absRow[s']*absCol[t'] * inv[r0+s'-7][c0+t'-7]} (replicate-clamped indices).
     */
    private static double accumulate(double[] absRow, double[] absCol, double[][] inv, int r0, int c0, int gh, int gw) {
        double sum = 0.0;
        for (int s = 0; s < PAT; s++) {
            double rw = absRow[s];
            if (rw == 0.0) {
                continue;
            }
            int rr = clamp(r0 + s - (CENTER - 1), gh);
            double[] invRow = inv[rr];
            double rowSum = 0.0;
            for (int t = 0; t < PAT; t++) {
                double cw = absCol[t];
                if (cw == 0.0) {
                    continue;
                }
                int cc = clamp(c0 + t - (CENTER - 1), gw);
                rowSum += cw * invRow[cc];
            }
            sum += rw * rowSum;
        }
        return sum;
    }

    /**
     * Returns {@code abs[u][s']} = {@code |sum_a filter[a] * A[u][s'+a-OFF]|} for u=0..7, s'=0..PAT-1.
     * This is the magnitude of the 1D wavelet response of DCT basis row {@code u}.
     */
    private static double[][] response(double[][] a, double[] filter) {
        double[][] abs = new double[8][PAT];
        for (int u = 0; u < 8; u++) {
            for (int s = 0; s < PAT; s++) {
                double acc = 0.0;
                for (int k = 0; k < LF; k++) {
                    int idx = s + k - OFF;
                    if (idx >= 0 && idx < 8) {
                        acc += filter[k] * a[u][idx];
                    }
                }
                abs[u][s] = Math.abs(acc);
            }
        }
        return abs;
    }

    /**
     * Computes {@code 1/(|W| + sigma)} over the block grid, where {@code W} is the plane filtered by
     * the separable centered correlation with {@code rowFilter} (down columns of the grid, i.e. the
     * vertical/row direction) and {@code colFilter} (across the row), using replicate padding.
     */
    private static double[][] invResidual(
            double[][] plane, int planeH, int planeW, int gh, int gw, double[] rowFilter, double[] colFilter) {
        // First filter horizontally (colFilter across columns), then vertically (rowFilter down
        // rows). Sample access clamps to the plane, so grid cells past the image replicate the edge.
        double[][] tmp = new double[gh][gw];
        for (int m = 0; m < gh; m++) {
            int sy = m < planeH ? m : planeH - 1;
            double[] prow = plane[sy];
            for (int n = 0; n < gw; n++) {
                double acc = 0.0;
                for (int b = 0; b < LF; b++) {
                    int sx = n + b - CENTER;
                    sx = sx < 0 ? 0 : (sx >= planeW ? planeW - 1 : sx);
                    acc += colFilter[b] * prow[sx];
                }
                tmp[m][n] = acc;
            }
        }
        double[][] inv = new double[gh][gw];
        for (int m = 0; m < gh; m++) {
            for (int n = 0; n < gw; n++) {
                double acc = 0.0;
                for (int aTap = 0; aTap < LF; aTap++) {
                    int ry = m + aTap - CENTER;
                    ry = ry < 0 ? 0 : (ry >= gh ? gh - 1 : ry);
                    acc += rowFilter[aTap] * tmp[ry][n];
                }
                inv[m][n] = 1.0 / (Math.abs(acc) + SIGMA);
            }
        }
        return inv;
    }

    /** The orthonormal 8-point DCT-II matrix {@code A[u][n]} (matches {@code Dct8x8}). */
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
}
