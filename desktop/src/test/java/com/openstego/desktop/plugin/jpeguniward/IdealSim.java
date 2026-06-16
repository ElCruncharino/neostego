/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.image.jpeg.JpegImage;

import java.util.Random;

/**
 * Optimal binary embedding simulator on the true SI-UNIWARD costs &mdash; the rate&ndash;distortion
 * bound for the distortion function, with no bootstrap header and no STC coder. Lives in the plugin
 * package so it can reuse the package-private {@link UniwardCost}. Each AC coefficient is changed
 * independently with probability {@code 1/(1+exp(lambda*rho))} (lambda by bisection so the total
 * coding entropy equals the payload bits), realised as the side-info {@code sign(e)} step.
 * Mutates the given {@link JpegImage} in place; returns {@code [changes, nonZeroAcCount]}.
 */
public final class IdealSim {

    private static final int AC_LIMIT = 1023;

    private IdealSim() {
    }

    public static long[] embed(JpegImage jpg, int payloadBytes, long seed) {
        return embed(jpg, payloadBytes, seed, 0);
    }

    /**
     * As {@link #embed(JpegImage, int, long)} but, after the optimal embedding, additionally flips
     * {@code blindChanges} AC coefficients chosen uniformly at random (cost-blind, still realised as
     * the side-info direction) &mdash; an emulation of a cost-blind bootstrap header, to quantify how
     * much such uncoded placement alone costs in detectability.
     */
    public static long[] embed(JpegImage jpg, int payloadBytes, long seed, int blindChanges) {
        int comps = jpg.getComponentCount();
        int total = 0;
        for (int c = 0; c < comps; c++) {
            total += jpg.getBlocksWide(c) * jpg.getBlocksHigh(c) * 63;
        }
        short[][] blk = new short[total][];
        int[] kk = new int[total];
        int[] dir = new int[total];
        double[] rho = new double[total];
        int idx = 0;
        long nz = 0;
        for (int c = 0; c < comps; c++) {
            double[][] plane = jpg.getPlane(c);
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            double[][] base = UniwardCost.compute(plane, plane.length, plane[0].length, bw, bh,
                    jpg.getQuantTable(c));
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    int bi = br * bw + bc;
                    short[] block = jpg.getBlock(c, br, bc);
                    double[] e = jpg.getRounding(c, br, bc);
                    double[] b = base[bi];
                    for (int k = 1; k < 64; k++) {
                        blk[idx] = block;
                        kk[idx] = k;
                        double ev = e[k];
                        dir[idx] = (ev > 0) ? 1 : (ev < 0 ? -1 : 1);
                        rho[idx] = b[k] * (1.0 - 2.0 * Math.abs(ev));
                        if (block[k] != 0) {
                            nz++;
                        }
                        idx++;
                    }
                }
            }
        }

        double lambda = solveLambda(rho, payloadBytes * 8.0);
        Random rng = new Random(seed);
        long chg = 0;
        for (int i = 0; i < total; i++) {
            double p = 1.0 / (1.0 + Math.exp(clip(lambda * rho[i])));
            if (rng.nextDouble() < p) {
                short[] b = blk[i];
                int k = kk[i];
                int v = b[k];
                int nv = v + dir[i];
                if (nv > AC_LIMIT || nv < -AC_LIMIT) {
                    nv = v - dir[i];
                }
                if (nv != v) {
                    b[k] = (short) nv;
                    chg++;
                }
            }
        }

        // Optional cost-blind header emulation: flip `blindChanges` uniformly-random AC coefficients.
        for (int h = 0; h < blindChanges; h++) {
            int i = rng.nextInt(total);
            short[] b = blk[i];
            int k = kk[i];
            int v = b[k];
            int nv = v + dir[i];
            if (nv > AC_LIMIT || nv < -AC_LIMIT) {
                nv = v - dir[i];
            }
            if (nv != v) {
                b[k] = (short) nv;
                chg++;
            }
        }
        return new long[] {chg, nz};
    }

    private static double clip(double x) {
        return x > 700.0 ? 700.0 : (x < -700.0 ? -700.0 : x);
    }

    private static double binEntropy(double p) {
        if (p <= 0.0 || p >= 1.0) {
            return 0.0;
        }
        double inv = 1.0 / Math.log(2);
        return (-p * Math.log(p) - (1 - p) * Math.log(1 - p)) * inv;
    }

    private static double solveLambda(double[] rho, double targetBits) {
        double lo = 1e-8, hi = 1e8;
        for (int it = 0; it < 60; it++) {
            double mid = Math.sqrt(lo * hi);
            double bits = 0.0;
            for (double r : rho) {
                bits += binEntropy(1.0 / (1.0 + Math.exp(clip(mid * r))));
            }
            if (bits > targetBits) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.sqrt(lo * hi);
    }
}
