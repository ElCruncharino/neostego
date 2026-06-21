/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import java.util.Arrays;
import java.util.Random;

/**
 * Pure-Java implementation of Syndrome-Trellis Codes (STC) for minimal-distortion steganographic
 * embedding, after Filler, Judas &amp; Fridrich, "Minimizing Additive Distortion in Steganography
 * Using Syndrome-Trellis Codes" (IEEE TIFS, 2011).
 * <p>
 * Given a binary cover vector {@code x} (the LSBs of the cover elements), a per-element change cost
 * {@code rho}, and a binary message {@code m}, {@link #embed} finds a stego vector {@code y} that
 * satisfies the syndrome constraint {@code H*y = m} (mod 2) while minimising the total distortion
 * {@code sum rho_i [x_i != y_i]}, using the dual-syndrome Viterbi trellis. Extraction is the cheap,
 * deterministic syndrome {@code m = H*y} computed by {@link #extract}.
 * <p>
 * The parity-check matrix {@code H} is the standard banded construction tiled from a small
 * {@code h x w} sub-matrix that is generated deterministically from {@code (h, w)} (so both sides
 * agree without storing it). Security comes from the keyed element permutation applied by the
 * caller, not from the matrix; the matrix is a shared constant. One message bit is carried per
 * {@code w} cover bits, i.e. the relative payload is {@code 1/w}.
 * <p>
 * This class is self-contained and platform independent (no AWT/Android types), so the identical
 * code runs on desktop and Android.
 */
public final class Stc {

    /** Default constraint height (trellis has {@code 2^h} states). 7 keeps memory modest while
     *  staying within ~0.1 bit of optimal coding efficiency. */
    public static final int DEFAULT_HEIGHT = 7;

    private static final double INF = Double.MAX_VALUE / 4.0;

    private Stc() {
        // Utility class
    }

    /**
     * Builds the {@code h x w} sub-matrix deterministically from {@code (h, w)}. Columns are
     * non-zero {@code h}-bit values; the first column is forced odd (so every block can match an
     * arbitrary message bit during pruning) and to have its top bit set (so the band spans the full
     * trellis height). Identical on the embed and extract side.
     *
     * @param h constraint height (2..30)
     * @param w block width (payload denominator); 1 or greater
     * @return the {@code w} columns, each an {@code h}-bit integer
     */
    static int[] buildSubmatrix(int h, int w) {
        int[] hh = new int[w];
        Random r = new Random(0x57C5EEDL ^ (h * 131L) ^ (w * 977L));
        int range = (1 << h) - 1;
        for (int c = 0; c < w; c++) {
            hh[c] = 1 + r.nextInt(range); // non-zero h-bit value
        }
        hh[0] |= 1; // odd -> toggles the syndrome LSB -> every block is prunable
        hh[0] |= (1 << (h - 1)); // top bit -> band reaches full height
        return hh;
    }

    /**
     * Returns the sub-matrix column for the given block, with any bits that would address a syndrome
     * row at or beyond the message length masked off. This terminates the band cleanly so the
     * trellis can always reach state 0 at the end.
     */
    private static int maskedColumn(int block, int c, int messageLen, int[] hhat, int h) {
        int col = hhat[c];
        int validBits = messageLen - block; // rows block..block+validBits-1 are in range
        if (validBits >= h) {
            return col;
        }
        if (validBits <= 0) {
            return 0;
        }
        return col & ((1 << validBits) - 1);
    }

    /**
     * Embeds {@code message} into the binary cover {@code x} with per-element costs {@code rho}.
     *
     * @param x       binary cover vector (values 0/1); length must equal {@code message.length * w}
     * @param rho     non-negative change cost per cover element; same length as {@code x}
     * @param message binary message vector (values 0/1)
     * @param w       block width; relative payload is {@code 1/w}
     * @param h       constraint height
     * @return the binary stego vector {@code y} (same length as {@code x}) with {@code H*y = message}
     */
    public static int[] embed(int[] x, double[] rho, int[] message, int w, int h) {
        int messageLen = message.length;
        int n = messageLen * w;
        if (x.length != n) {
            throw new IllegalArgumentException("cover length " + x.length + " != message*w " + n);
        }
        if (rho.length != n) {
            throw new IllegalArgumentException("cost length " + rho.length + " != cover length " + n);
        }
        int[] hhat = buildSubmatrix(h, w);
        int nstates = 1 << h;
        int statesWords = (nstates + 63) >> 6;

        double[] wght = new double[nstates];
        Arrays.fill(wght, INF);
        wght[0] = 0.0;
        double[] next = new double[nstates];

        // path[p] holds, per state k, which incoming choice (y=0/1) was optimal, packed as bits
        long[] path = new long[n * statesWords];

        int blk = 0;
        for (int p = 0; p < n; p++) {
            int c = p % w;
            int col = maskedColumn(p / w, c, messageLen, hhat, h);
            int base = p * statesWords;
            int xp = x[p];
            double cost = rho[p];
            // xp is constant across the state loop, so the two y-choice penalties are loop invariants:
            // exactly one of them is 'cost' (the one that flips the bit), the other 0.
            double add0 = (xp == 1) ? cost : 0.0; // choose y=0 (differs from x if x=1)
            double add1 = (xp == 0) ? cost : 0.0; // choose y=1 (differs from x if x=0)
            // Accumulate each 64-state group's decision bits in a register and store the word once,
            // instead of a read-modify-write into path[] per chosen state. path is zero-initialized and
            // each word is written exactly once, so the plain store is equivalent to the prior OR.
            for (int wd = 0; wd < statesWords; wd++) {
                int k0 = wd << 6;
                int kEnd = Math.min(nstates, k0 + 64);
                long bits = 0L;
                for (int k = k0; k < kEnd; k++) {
                    double w0 = wght[k] + add0;
                    double w1 = wght[k ^ col] + add1;
                    if (w1 < w0) {
                        next[k] = w1;
                        bits |= 1L << (k & 63);
                    } else {
                        next[k] = w0;
                    }
                }
                path[base + wd] = bits;
            }
            double[] tmp = wght;
            wght = next;
            next = tmp;

            if (c == w - 1) {
                int mbit = message[blk];
                int half = nstates >> 1;
                for (int k = 0; k < half; k++) {
                    wght[k] = wght[(k << 1) | mbit];
                }
                Arrays.fill(wght, half, nstates, INF);
                blk++;
            }
        }

        // Backtrack from the terminal state 0
        int[] y = new int[n];
        int state = 0;
        int mi = messageLen - 1;
        for (int p = n - 1; p >= 0; p--) {
            int c = p % w;
            int b = p / w;
            int col = maskedColumn(b, c, messageLen, hhat, h);
            if (c == w - 1) {
                state = (state << 1) | message[mi];
                mi--;
            }
            int base = p * statesWords;
            int bit = (int) ((path[base + (state >> 6)] >> (state & 63)) & 1L);
            y[p] = bit;
            if (bit == 1) {
                state ^= col;
            }
        }
        return y;
    }

    /**
     * Extracts the message from a stego vector by computing the syndrome {@code m = H*y}.
     *
     * @param y          binary stego vector (values 0/1); length must be {@code messageLen * w}
     * @param messageLen number of message bits
     * @param w          block width used at embed time
     * @param h          constraint height used at embed time
     * @return the recovered binary message (length {@code messageLen})
     */
    public static int[] extract(int[] y, int messageLen, int w, int h) {
        if (y.length != messageLen * w) {
            throw new IllegalArgumentException("stego length " + y.length + " != message*w " + (messageLen * w));
        }
        int[] hhat = buildSubmatrix(h, w);
        int[] m = new int[messageLen];
        for (int p = 0; p < y.length; p++) {
            if (y[p] == 0) {
                continue;
            }
            int b = p / w;
            int col = maskedColumn(b, p % w, messageLen, hhat, h);
            int t = 0;
            while (col != 0) {
                if ((col & 1) != 0) {
                    m[b + t] ^= 1;
                }
                col >>= 1;
                t++;
            }
        }
        return m;
    }

    /**
     * Chooses the largest block width {@code w} (lowest, safest payload) such that {@code messageLen}
     * message bits fit into {@code coverLen} cover elements. Returns 0 if the message cannot fit even
     * at full payload.
     *
     * @param coverLen   number of available cover elements (bits)
     * @param messageLen number of message bits to embed
     * @return block width {@code w} (1 or greater), or 0 if capacity is exceeded
     */
    public static int chooseWidth(int coverLen, int messageLen) {
        if (messageLen <= 0) {
            return 1;
        }
        int w = coverLen / messageLen;
        return Math.max(0, w);
    }
}
