/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.bench;

import com.openstego.desktop.plugin.adaptive.Stc;

import java.util.Random;

/**
 * Isolated coding-efficiency probe for the syndrome-trellis code, independent of the JPEG pipeline.
 * Embeds a random message into a random binary cover with random costs at a chosen relative payload
 * {@code 1/w}, for a sweep of constraint heights, and reports the achieved embedding efficiency
 * (message bits per coefficient change) against the rate-distortion bound. A healthy STC should sit
 * close to the bound; a large gap means the trellis (height or submatrix design) is the bottleneck.
 *
 * Usage: {@code StcBench [messageLen] [w1,w2,...] [h1,h2,...]}
 */
public final class StcBench {

    public static void main(String[] args) {
        if (args.length > 0 && "brute".equals(args[0])) {
            bruteCheck();
            return;
        }
        int messageLen = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        int[] ws = args.length > 1 ? parseInts(args[1]) : new int[] {5, 10, 25, 50, 96};
        int[] hs = args.length > 2 ? parseInts(args[2]) : new int[] {7, 10, 12, 14};

        System.out.printf("STC efficiency probe  (messageLen=%d)%n", messageLen);
        System.out.printf("%6s %8s %6s %10s %10s %10s %8s%n",
                "w", "alpha", "h", "changes", "bits/chg", "bound b/c", "loss%");
        for (int w : ws) {
            int n = messageLen * w;
            double alpha = 1.0 / w;
            double boundBeta = invH(alpha);                 // optimal change rate for this payload
            double boundEff = alpha / boundBeta;            // optimal bits per change
            for (int h : hs) {
                int[] x = new int[n];
                double[] rho = new double[n];
                int[] msg = new int[messageLen];
                Random r = new Random(12345L + 7L * w + h);
                for (int i = 0; i < n; i++) {
                    x[i] = r.nextInt(2);
                    rho[i] = r.nextDouble() + 1e-3;          // uniform positive costs
                }
                for (int i = 0; i < messageLen; i++) {
                    msg[i] = r.nextInt(2);
                }
                int[] y = Stc.embed(x, rho, msg, w, h);
                int[] chk = Stc.extract(y, messageLen, w, h);
                boolean ok = true;
                for (int i = 0; i < messageLen; i++) {
                    if (chk[i] != msg[i]) {
                        ok = false;
                        break;
                    }
                }
                int changes = 0;
                for (int i = 0; i < n; i++) {
                    if (y[i] != x[i]) {
                        changes++;
                    }
                }
                double eff = (double) messageLen / Math.max(1, changes);
                double loss = 100.0 * (boundEff - eff) / boundEff;
                System.out.printf("%6d %8.4f %6d %10d %10.2f %10.2f %7.1f%s%n",
                        w, alpha, h, changes, eff, boundEff, loss, ok ? "" : "  EXTRACT-FAIL");
            }
        }
    }

    /**
     * Compares the trellis solution against the true minimum-distortion solution found by brute force,
     * on instances small enough to enumerate all 2^n stego vectors. If the STC cost exceeds the brute
     * minimum, the trellis is not finding the optimal path (a coding bug, not a tuning issue).
     */
    private static void bruteCheck() {
        int[][] cases = {{2, 4, 4}, {3, 3, 4}, {4, 3, 5}, {2, 6, 5}, {5, 2, 4}};
        System.out.printf("%4s %4s %4s %10s %10s %8s%n", "w", "mlen", "h", "stcCost", "bruteCost", "match");
        for (int[] cs : cases) {
            int w = cs[0], mlen = cs[1], h = cs[2];
            int n = w * mlen;
            Random r = new Random(99L + 31L * w + 7L * mlen + h);
            int[] x = new int[n];
            double[] rho = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = r.nextInt(2);
                rho[i] = Math.round((r.nextDouble() + 0.05) * 100) / 100.0;
            }
            int[] msg = new int[mlen];
            for (int i = 0; i < mlen; i++) {
                msg[i] = r.nextInt(2);
            }
            int[] y = Stc.embed(x, rho, msg, w, h);
            double stcCost = cost(x, y, rho);
            // Brute force: enumerate all y, keep those with H*y == msg, take min cost.
            double bruteCost = Double.POSITIVE_INFINITY;
            for (long mask = 0; mask < (1L << n); mask++) {
                int[] cand = new int[n];
                for (int i = 0; i < n; i++) {
                    cand[i] = (int) ((mask >> i) & 1L);
                }
                int[] syn = Stc.extract(cand, mlen, w, h);
                boolean ok = true;
                for (int i = 0; i < mlen; i++) {
                    if (syn[i] != msg[i]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    bruteCost = Math.min(bruteCost, cost(x, cand, rho));
                }
            }
            boolean match = Math.abs(stcCost - bruteCost) < 1e-9;
            System.out.printf("%4d %4d %4d %10.2f %10.2f %8s%n", w, mlen, h, stcCost, bruteCost,
                    match ? "OK" : "SUBOPT");
        }
    }

    private static double cost(int[] x, int[] y, double[] rho) {
        double c = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) {
                c += rho[i];
            }
        }
        return c;
    }

    /** Inverse binary entropy: smallest beta in (0,0.5] with H(beta) = target. */
    private static double invH(double target) {
        double lo = 1e-9, hi = 0.5;
        for (int it = 0; it < 100; it++) {
            double mid = 0.5 * (lo + hi);
            if (binEntropy(mid) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double binEntropy(double p) {
        if (p <= 0 || p >= 1) {
            return 0.0;
        }
        return -p * log2(p) - (1 - p) * log2(1 - p);
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    private static int[] parseInts(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
