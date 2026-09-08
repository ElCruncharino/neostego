/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.util.dwt.Image;
import com.openstego.desktop.util.svd.Svd;
import java.util.function.DoubleConsumer;

/**
 * QIM-on-largest-singular-value channel shared by {@link DWTSVDPlugin} (a fixed watermark, verified by
 * correlation) and {@link RobustPlugin} (an arbitrary message, recovered exactly): each 8&times;8 block
 * of a DWT LL sub-band carries one code bit, quantizing (embed) or reading (extract) the block's largest
 * singular value. Blocks are addressed by a password-keyed, <em>position-absolute</em> hash of their
 * (row, col) -- not a permutation over the block count -- so a block that survives a crop still carries
 * the same code index, which is what lets both callers' alignment search resynchronise after a
 * crop/translation without any separately-embedded synchronization signal.
 */
final class SvdQimChannel {

    /** Side of the square block used for the SVD (in LL sub-band pixels). */
    static final int BLOCK = 8;

    private SvdQimChannel() {}

    /**
     * Deterministic, password-keyed, position-absolute mapping from a block's (row, col) to a code-bit
     * index. SplitMix64-style avalanche gives a near-uniform spread of code indices across blocks (so
     * each bit is repetition-tiled many times) while depending only on the absolute coordinates -- not
     * the image size -- so a crop that removes border blocks leaves the remaining assignments intact.
     */
    static int codeIndexForBlock(long seed, int row, int col, int codeLen) {
        long h = seed + 0x9E3779B97F4A7C15L * (row + 1) + 0xC2B2AE3D27D4EB4FL * (col + 1);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (int) Math.floorMod(h, (long) codeLen);
    }

    /**
     * Embeds {@code codeBits} into every 8x8 block of {@code ll}, two passes: first the global reference
     * mu = mean(largest singular value) so the QIM step scales with the signal (gain robustness), then
     * the actual per-block quantization.
     */
    static void embedCodeBits(
            Image ll,
            int[] codeBits,
            long seed,
            double strength,
            DoubleConsumer progress,
            String namespace,
            int tooSmallErrorCode)
            throws OpenStegoException {
        int blocksW = ll.getWidth() / BLOCK;
        int blocksH = ll.getHeight() / BLOCK;
        int numBlocks = blocksW * blocksH;
        if (numBlocks < codeBits.length) {
            throw new OpenStegoException(null, namespace, tooSmallErrorCode);
        }

        double sum = 0.0;
        for (int br = 0; br < blocksH; br++) {
            for (int bc = 0; bc < blocksW; bc++) {
                sum += Svd.largestSingularValue(getBlock(ll, br, bc));
            }
            progress.accept(0.5 * (br + 1.0) / blocksH);
        }
        double mu = sum / numBlocks;
        if (mu < 1e-6) {
            throw new OpenStegoException(null, namespace, tooSmallErrorCode);
        }
        double step = strength * mu;

        for (int br = 0; br < blocksH; br++) {
            for (int bc = 0; bc < blocksW; bc++) {
                int idx = codeIndexForBlock(seed, br, bc, codeBits.length);
                int bit = codeBits[idx];
                Svd svd = new Svd(getBlock(ll, br, bc));
                double newS0 = quantize(svd.getSingularValue(0), step, bit);
                svd.setSingularValue(0, newS0);
                putBlock(ll, br, bc, svd.reconstruct());
            }
            progress.accept(0.5 + 0.5 * (br + 1.0) / blocksH);
        }
    }

    /**
     * Decomposes every 8x8 block at grid phase {@code (phaseY, phaseX)} and returns its largest singular
     * value. This is the expensive part of a decode (one SVD per block) and depends only on the phase,
     * so an alignment search computes it once per phase and reuses it across all block-origin offsets.
     */
    static double[][] computeS0Grid(Image ll, int phaseY, int phaseX) {
        int gridH = (ll.getHeight() - phaseY) / BLOCK;
        int gridW = (ll.getWidth() - phaseX) / BLOCK;
        double[][] s0 = new double[gridH][gridW];
        for (int r = 0; r < gridH; r++) {
            for (int c = 0; c < gridW; c++) {
                s0[r][c] = Svd.largestSingularValue(getBlockAt(ll, phaseY + r * BLOCK, phaseX + c * BLOCK));
            }
        }
        return s0;
    }

    /** The QIM step for a grid: {@code strength} times the mean largest singular value over its blocks. */
    static double stepFor(double[][] s0, double strength) {
        double sum = 0.0;
        int n = 0;
        for (double[] row : s0) {
            for (double v : row) {
                sum += v;
                n++;
            }
        }
        return strength * (sum / n);
    }

    /**
     * Recovers hard-decision code bits from a precomputed singular-value grid: each block votes (by the
     * parity of its QIM-decoded largest singular value) for the absolutely-addressed code index it
     * carries, shifted by the block-origin offset {@code (offR, offC)}; the majority over all
     * repetitions gives each bit. {@link #voteConfidence} does the same walk with a soft-decision score.
     */
    static int[] voteCodeBits(double[][] s0, double step, long seed, int offR, int offC, int codeLen) {
        int gridH = s0.length;
        int gridW = s0[0].length;
        int[] votesFor1 = new int[codeLen];
        int[] votesFor0 = new int[codeLen];
        for (int r = 0; r < gridH; r++) {
            for (int c = 0; c < gridW; c++) {
                int idx = codeIndexForBlock(seed, r + offR, c + offC, codeLen);
                if (decodeBit(s0[r][c], step) == 1) {
                    votesFor1[idx]++;
                } else {
                    votesFor0[idx]++;
                }
            }
        }
        int[] codeBits = new int[codeLen];
        for (int i = 0; i < codeLen; i++) {
            codeBits[i] = (votesFor1[i] > votesFor0[i]) ? 1 : 0;
        }
        return codeBits;
    }

    /**
     * As {@link #voteCodeBits}, but each block's vote is weighted by its confidence -- how far its
     * singular value sits from the nearest QIM decision boundary, relative to the step size. A block
     * whose value landed right on a bin edge (easily flipped by a small perturbation) counts for less
     * than one deep in a bin's interior; a hard-decision majority vote treats both the same. This is a
     * standard soft-decision refinement (not this project's invention), applied to our own repetition
     * scheme rather than copied from any specific tool's implementation of it.
     */
    static int[] voteCodeBitsWeighted(double[][] s0, double step, long seed, int offR, int offC, int codeLen) {
        int gridH = s0.length;
        int gridW = s0[0].length;
        double[] scoreFor1 = new double[codeLen];
        double[] scoreFor0 = new double[codeLen];
        for (int r = 0; r < gridH; r++) {
            for (int c = 0; c < gridW; c++) {
                int idx = codeIndexForBlock(seed, r + offR, c + offC, codeLen);
                double value = s0[r][c];
                long q = Math.round(value / step);
                double distanceFromBoundary = Math.abs(value / step - q); // in [0, 0.5]
                double confidence = 2.0 * distanceFromBoundary; // in [0, 1]
                if ((q & 1L) == 1) {
                    scoreFor1[idx] += confidence;
                } else {
                    scoreFor0[idx] += confidence;
                }
            }
        }
        int[] codeBits = new int[codeLen];
        for (int i = 0; i < codeLen; i++) {
            codeBits[i] = (scoreFor1[i] > scoreFor0[i]) ? 1 : 0;
        }
        return codeBits;
    }

    /** QIM embed: return the nearest multiple of {@code step} whose index parity equals {@code bit}. */
    static double quantize(double value, double step, int bit) {
        long q = Math.round(value / step);
        if ((q & 1L) != (bit & 1)) {
            double lower = (q - 1) * step;
            double upper = (q + 1) * step;
            q += (Math.abs(value - lower) <= Math.abs(value - upper)) ? -1 : 1;
        }
        return q * step;
    }

    /** QIM decode: the parity of the nearest quantizer index gives the bit. */
    static int decodeBit(double value, double step) {
        long q = Math.round(value / step);
        return (int) (q & 1L);
    }

    static double[][] getBlock(Image ll, int br, int bc) {
        return getBlockAt(ll, br * BLOCK, bc * BLOCK);
    }

    /** Reads an 8x8 block whose top-left corner is at LL pixel ({@code originY}, {@code originX}). */
    static double[][] getBlockAt(Image ll, int originY, int originX) {
        int width = ll.getWidth();
        double[] data = ll.getData();
        double[][] blk = new double[BLOCK][BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            int y = originY + i;
            for (int j = 0; j < BLOCK; j++) {
                int x = originX + j;
                blk[i][j] = data[y * width + x];
            }
        }
        return blk;
    }

    static void putBlock(Image ll, int br, int bc, double[][] blk) {
        int width = ll.getWidth();
        double[] data = ll.getData();
        for (int i = 0; i < BLOCK; i++) {
            int y = br * BLOCK + i;
            for (int j = 0; j < BLOCK; j++) {
                int x = bc * BLOCK + j;
                data[y * width + x] = blk[i][j];
            }
        }
    }

    static int[] bytesToBits(byte[] bytes) {
        int[] bits = new int[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            for (int j = 0; j < 8; j++) {
                bits[i * 8 + j] = (b >> (7 - j)) & 1;
            }
        }
        return bits;
    }

    static byte[] bitsToBytes(int[] bits) {
        byte[] bytes = new byte[bits.length / 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bits[i * 8 + j] & 1);
            }
            bytes[i] = (byte) b;
        }
        return bytes;
    }
}
