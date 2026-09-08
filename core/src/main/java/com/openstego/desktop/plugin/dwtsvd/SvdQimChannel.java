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
 * <p>
 * {@link RobustPlugin} additionally survives a rescale, which per-block addressing cannot: no matter how
 * a block is addressed, resampling blends together a neighborhood of adjacent blocks, so any signal that
 * lives at single-block granularity is partly destroyed by the resample itself, not just misindexed by
 * it. Surviving that requires a signal that lives at a coarser scale than the resampling kernel to begin
 * with -- see {@link #embedCodeBitsDualAddress} for how the message is layered a second time as a handful
 * of low-frequency 2-D DCT coefficients of the whole block-energy grid.
 */
final class SvdQimChannel {

    /** Side of the square block used for the SVD (in LL sub-band pixels). */
    static final int BLOCK = 8;

    private SvdQimChannel() {}

    /** Maps a block's (row, col) to a code-bit index; swappable so embed/vote logic isn't duplicated per scheme. */
    private interface BlockAddressing {
        int indexFor(int row, int col);
    }

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
        embedBlocks(
                ll,
                codeBits,
                codeBits.length,
                strength,
                progress,
                namespace,
                tooSmallErrorCode,
                (row, col) -> codeIndexForBlock(seed, row, col, codeBits.length));
    }

    /**
     * As {@link #embedCodeBits}, but the message is embedded twice over, under two independent mechanisms
     * layered on the <em>same</em> blocks rather than splitting the grid between them: absolute per-block
     * QIM (see {@link #codeIndexForBlock}), which survives a crop, embeds first; low-frequency 2-D DCT
     * coefficients of the whole block-energy grid (see {@link #dctEmbed}), which survives a rescale, are
     * layered on top of that.
     * <p>
     * The two mechanisms cannot simply be quantized independently and summed: both re-quantize the same
     * scalar (a block's largest singular value), so naively applying one after the other lets the second
     * pick a value nowhere near what the first chose, erasing it. Splitting blocks between the two
     * mechanisms instead of layering them was tried first and halved each one's redundancy for no
     * benefit -- neither reliably survived its own attack anymore, let alone the other's. The fix actually
     * used is a two-level (nested) quantizer, the standard way to carry two independent signals on one
     * value: the DCT layer only ever moves a block by an exact even multiple of the QIM step ({@code
     * dctEmbed}'s {@code qimStep} parameter), so it can never cross a QIM decision boundary -- the QIM
     * layer's parity survives exactly, and the DCT layer approximates its target to within one QIM step,
     * negligible next to the QIM step being over an order of magnitude smaller than the DCT one.
     */
    static void embedCodeBitsDualAddress(
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
        if (blocksW * blocksH < codeBits.length) {
            throw new OpenStegoException(null, namespace, tooSmallErrorCode);
        }
        double[][] s0 = computeS0Grid(ll, 0, 0);
        double[][] qimStepGrid = localStepGrid(s0, strength);

        for (int br = 0; br < blocksH; br++) {
            for (int bc = 0; bc < blocksW; bc++) {
                if (isRisky(s0[br][bc], qimStepGrid[br][bc])) {
                    continue; // near black/white: quantizing here risks the final 0..255 pixel clamp
                }
                int idx = codeIndexForBlock(seed, br, bc, codeBits.length);
                int bit = codeBits[idx];
                Svd svd = new Svd(getBlock(ll, br, bc));
                double newS0 = quantize(svd.getSingularValue(0), qimStepGrid[br][bc], bit);
                svd.setSingularValue(0, newS0);
                putBlock(ll, br, bc, svd.reconstruct());
            }
            progress.accept(0.5 * (br + 1.0) / blocksH);
        }
        dctEmbed(ll, codeBits, seed, blocksH, blocksW, qimStepGrid);
        progress.accept(1.0);
    }

    /**
     * True when a block's own largest singular value is small enough, relative to its own quantization
     * step, that {@link #quantize} could plausibly need to push it through (or very close to) zero to
     * reach the nearest correctly-paritied grid point. A block that dark or that flat has essentially no
     * room to absorb that move: the final inverse-DWT pixel clamp to [0, 255] corrupts it instead of
     * reproducing the intended value, and unlike ordinary attack noise this isn't a small, randomly
     * distributed error rate that redundancy and Reed-Solomon can outvote -- real photos can have this
     * happen to a large, systematic fraction of all blocks (a flat sky, a shadowed background). Leaving
     * such a block unmodified sacrifices its one vote/bit but avoids planting a wrong one; there is no
     * corresponding case for the *bright* end because a block's largest singular value has no fixed
     * upper bound to run into.
     */
    private static boolean isRisky(double s0Value, double step) {
        return s0Value < 2.0 * step;
    }

    /**
     * Neighborhood-averaged step per block: {@code strength} times the mean largest singular value over a
     * small window centered on that block, instead of one global mean over the whole grid. A real photo's
     * block energy is wildly uneven -- a dark, flat background block's own singular value can be a tiny
     * fraction of a single global mean skewed high by a few bright, detailed blocks. Quantizing such a
     * block against that oversized global step forces it through a change many multiples of its own
     * natural scale to reach the nearest correctly-paritied grid point; the inverse DWT's final 0..255
     * pixel clamp then corrupts that block irrecoverably (a synthetic, evenly-textured test cover never
     * exercises this). A local step tracks each block's own neighborhood instead, so the forced change
     * stays proportional to what that neighborhood can actually absorb. Both embed and decode compute this
     * fresh from whatever grid they can see, so it needs no side information, and stays robust to a
     * brightness/gain attack over the whole image exactly like the single-step version did.
     */
    private static final int STEP_WINDOW = 15;

    static double[][] localStepGrid(double[][] s0, double strength) {
        int h = s0.length;
        int w = s0[0].length;
        double[][] prefix = new double[h + 1][w + 1];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                prefix[r + 1][c + 1] = s0[r][c] + prefix[r][c + 1] + prefix[r + 1][c] - prefix[r][c];
            }
        }
        int half = STEP_WINDOW / 2;
        double[][] stepGrid = new double[h][w];
        for (int r = 0; r < h; r++) {
            int r0 = Math.max(0, r - half);
            int r1 = Math.min(h - 1, r + half);
            for (int c = 0; c < w; c++) {
                int c0 = Math.max(0, c - half);
                int c1 = Math.min(w - 1, c + half);
                double sum = prefix[r1 + 1][c1 + 1] - prefix[r0][c1 + 1] - prefix[r1 + 1][c0] + prefix[r0][c0];
                int count = (r1 - r0 + 1) * (c1 - c0 + 1);
                // Floored: an all-zero (pure black) neighborhood would otherwise give a zero step, and
                // dividing by it in quantize()/decodeBit() blows up to NaN/Infinity.
                stepGrid[r][c] = Math.max(strength * (sum / count), 1e-6);
            }
        }
        return stepGrid;
    }

    private static void embedBlocks(
            Image ll,
            int[] codeBits,
            int minBlocks,
            double strength,
            DoubleConsumer progress,
            String namespace,
            int tooSmallErrorCode,
            BlockAddressing addressing)
            throws OpenStegoException {
        int blocksW = ll.getWidth() / BLOCK;
        int blocksH = ll.getHeight() / BLOCK;
        int numBlocks = blocksW * blocksH;
        if (numBlocks < minBlocks) {
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
                int bit = codeBits[addressing.indexFor(br, bc)];
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

    /** Highest 2-D DCT frequency index (exclusive) of the block-energy grid that {@link #dctEmbed} uses. */
    private static final int DCT_BAND = 64;

    /**
     * Relative QIM step for the DCT-domain scheme. Independent of, and deliberately much larger than,
     * {@link RobustPlugin}'s own per-block QIM strength: the nested quantizer in {@link #dctEmbed} needs
     * that gap to keep its one-{@code qimStep} rounding error small relative to this step.
     */
    private static final double DCT_STRENGTH = 0.10;

    /**
     * Embeds {@code codeBits} into the low-frequency band of a 2-D DCT of the block-energy grid (the
     * largest singular value of every block), instead of into individual blocks. A resample is a low-pass
     * filter: it barely touches a signal's low frequencies while destroying its high ones, so a signal
     * carried at low spatial frequency across the whole grid survives a rescale that per-block addressing
     * (living at a single block's frequency) cannot -- no matter how that block is addressed, a resample
     * blends it with its neighbors before any addressing scheme gets to read it back.
     * <p>
     * Each coefficient is QIM-quantized exactly like a block's singular value elsewhere in this class;
     * {@link #codeIndexForBlock} (reused, keyed by frequency indices instead of block coordinates) spreads
     * each code bit across several coefficients for the same repetition-based redundancy as the per-block
     * schemes. The modified coefficient grid is inverse-DCT'd back into a target block-energy grid; every
     * block is then nudged <em>towards</em> that target, not onto it -- see {@code qimStep} below.
     * <p>
     * Run after {@link #embedCodeBitsDualAddress}'s per-block QIM layer, every block already sits on a
     * value whose parity (relative to that block's own entry in {@code qimStepGrid}) encodes that layer's
     * bit. Moving a block straight to its DCT target would land on an arbitrary point and likely flip that
     * parity, erasing the QIM layer. Instead each block moves by {@code round((target - current) / (2 *
     * qimStepGrid[r][c])) * (2 * qimStepGrid[r][c])} -- the nearest whole number of full QIM periods for
     * that block -- which by construction preserves the exact value modulo its own period, and therefore
     * its QIM parity, while approximating the DCT target to within one local QIM step: negligible as long
     * as the DCT step is chosen well above it (see {@link #DCT_STRENGTH} relative to {@link RobustPlugin}'s
     * own QIM strength).
     */
    private static void dctEmbed(
            Image ll, int[] codeBits, long seed, int blocksH, int blocksW, double[][] qimStepGrid) {
        double[][] s0 = new double[blocksH][blocksW];
        for (int r = 0; r < blocksH; r++) {
            for (int c = 0; c < blocksW; c++) {
                s0[r][c] = Svd.largestSingularValue(getBlock(ll, r, c));
            }
        }
        double step = DCT_STRENGTH * mean(s0);

        double[][] coeff = dct2d(s0);
        int fu = Math.min(DCT_BAND, coeff.length);
        int fv = Math.min(DCT_BAND, coeff[0].length);
        for (int u = 1; u < fu; u++) {
            for (int v = 1; v < fv; v++) {
                int idx = dctCodeIndex(seed, u, v, codeBits.length);
                coeff[u][v] = quantize(coeff[u][v], step, codeBits[idx]);
            }
        }
        double[][] target = idct2d(coeff);

        for (int r = 0; r < blocksH; r++) {
            for (int c = 0; c < blocksW; c++) {
                if (isRisky(s0[r][c], qimStepGrid[r][c])) {
                    continue; // same near-black/white exclusion as the QIM layer -- see isRisky
                }
                double period = 2.0 * qimStepGrid[r][c];
                double delta = Math.round((target[r][c] - s0[r][c]) / period) * period;
                Svd svd = new Svd(getBlock(ll, r, c));
                svd.setSingularValue(0, s0[r][c] + delta);
                putBlock(ll, r, c, svd.reconstruct());
            }
        }
    }

    /**
     * As {@link #dctEmbed}, but reading: DCTs a precomputed block-energy grid and votes each low-frequency
     * coefficient's QIM parity, confidence-weighted exactly as {@link #voteCodeBitsWeighted} does for
     * individual blocks.
     */
    static int[] voteCodeBitsDct(double[][] s0, long seed, int codeLen) {
        double step = DCT_STRENGTH * mean(s0);

        double[][] coeff = dct2d(s0);
        int fu = Math.min(DCT_BAND, coeff.length);
        int fv = Math.min(DCT_BAND, coeff[0].length);
        double[] scoreFor1 = new double[codeLen];
        double[] scoreFor0 = new double[codeLen];
        for (int u = 1; u < fu; u++) {
            for (int v = 1; v < fv; v++) {
                int idx = dctCodeIndex(seed, u, v, codeLen);
                double value = coeff[u][v];
                long q = Math.round(value / step);
                double confidence = 2.0 * Math.abs(value / step - q);
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

    private static int dctCodeIndex(long seed, int u, int v, int codeLen) {
        return codeIndexForBlock(seed ^ 0x27D4EB2F165667C5L, u, v, codeLen);
    }

    private static double mean(double[][] grid) {
        double sum = 0.0;
        int n = 0;
        for (double[] row : grid) {
            for (double v : row) {
                sum += v;
                n++;
            }
        }
        return sum / n;
    }

    /** Separable, orthonormal 2-D DCT-II (naive O(n^2) per axis -- the block-energy grid is small). */
    private static double[][] dct2d(double[][] grid) {
        int h = grid.length;
        int w = grid[0].length;
        double[][] rowT = new double[h][];
        for (int r = 0; r < h; r++) {
            rowT[r] = dct1d(grid[r]);
        }
        double[][] out = new double[h][w];
        for (int c = 0; c < w; c++) {
            double[] col = new double[h];
            for (int r = 0; r < h; r++) {
                col[r] = rowT[r][c];
            }
            double[] colT = dct1d(col);
            for (int r = 0; r < h; r++) {
                out[r][c] = colT[r];
            }
        }
        return out;
    }

    /** Inverse of {@link #dct2d}: the orthonormal DCT-II's own transpose, so the same shape both ways. */
    private static double[][] idct2d(double[][] coeff) {
        int h = coeff.length;
        int w = coeff[0].length;
        double[][] colInv = new double[h][w];
        for (int c = 0; c < w; c++) {
            double[] col = new double[h];
            for (int r = 0; r < h; r++) {
                col[r] = coeff[r][c];
            }
            double[] colI = idct1d(col);
            for (int r = 0; r < h; r++) {
                colInv[r][c] = colI[r];
            }
        }
        double[][] out = new double[h][w];
        for (int r = 0; r < h; r++) {
            out[r] = idct1d(colInv[r]);
        }
        return out;
    }

    private static double[] dct1d(double[] x) {
        int n = x.length;
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                sum += x[i] * Math.cos(Math.PI / n * (i + 0.5) * k);
            }
            out[k] = sum * (k == 0 ? Math.sqrt(1.0 / n) : Math.sqrt(2.0 / n));
        }
        return out;
    }

    private static double[] idct1d(double[] coeff) {
        int n = coeff.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            for (int k = 0; k < n; k++) {
                double a = (k == 0 ? Math.sqrt(1.0 / n) : Math.sqrt(2.0 / n));
                sum += a * coeff[k] * Math.cos(Math.PI / n * (i + 0.5) * k);
            }
            out[i] = sum;
        }
        return out;
    }

    /**
     * Recovers hard-decision code bits from a precomputed singular-value grid: each block votes (by the
     * parity of its QIM-decoded largest singular value) for the absolutely-addressed code index it
     * carries, shifted by the block-origin offset {@code (offR, offC)}; the majority over all
     * repetitions gives each bit. {@link #voteCodeBitsWeighted} does the same walk with a soft-decision score.
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
     * <p>
     * Uses a {@link #localStepGrid} exactly as the dual-address embed does, recomputed fresh from the
     * received grid -- needs no side information about the original cover.
     */
    static int[] voteCodeBitsWeighted(double[][] s0, double strength, long seed, int offR, int offC, int codeLen) {
        double[][] stepGrid = localStepGrid(s0, strength);
        int gridH = s0.length;
        int gridW = gridH == 0 ? 0 : s0[0].length;
        double[] scoreFor1 = new double[codeLen];
        double[] scoreFor0 = new double[codeLen];
        for (int r = 0; r < gridH; r++) {
            for (int c = 0; c < gridW; c++) {
                double value = s0[r][c];
                double step = stepGrid[r][c];
                if (isRisky(value, step)) {
                    continue; // same exclusion embed applied; this block was never carrying real signal
                }
                int idx = codeIndexForBlock(seed, r + offR, c + offC, codeLen);
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
