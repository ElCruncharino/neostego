/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.YuvImageUtil;
import com.openstego.desktop.plugin.template.image.WMImagePluginTemplate;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.StringUtil;
import com.openstego.desktop.util.dwt.DWT;
import com.openstego.desktop.util.dwt.Image;
import com.openstego.desktop.util.dwt.ImageTree;
import com.openstego.desktop.util.ecc.ReedSolomon;
import com.openstego.desktop.util.svd.Svd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/**
 * Plugin for NeoStego which implements a modern blind, multi-bit, robust image watermark.
 * <p>
 * Unlike the legacy spread-spectrum DWT plugins (which only detect the <em>presence</em> of a password-keyed pattern via
 * correlation and carry no message), this plugin embeds an actual payload that can be recovered blindly (without the
 * original cover image) and survives common image processing such as JPEG re-compression, additive noise, blurring and
 * mild scaling.
 * <p>
 * Pipeline: the payload is protected with a Reed&ndash;Solomon code, the resulting code bits are scrambled with a
 * password-derived permutation and repetition-tiled across the image. Each bit is embedded by quantizing
 * (Quantization Index Modulation) the largest singular value of an 8&times;8 block of the level-1 DWT approximation
 * (LL) sub-band of the luminance channel. Extraction recomputes the singular values blockwise, recovers each code bit by
 * majority vote over its repetitions, and Reed&ndash;Solomon-decodes the payload.
 * <p>
 * References: hybrid DWT&ndash;SVD watermarking with QIM on the largest singular value is a well-established robust,
 * blind scheme (see e.g. Kang et al., Multimedia Tools Appl. 77, 2018; and recent DWT&ndash;SVD&ndash;QIM evaluations,
 * 2024&ndash;2025).
 */
public class DWTSVDPlugin extends WMImagePluginTemplate {

    private static final LabelUtil labelUtil = LabelUtil.getInstance(DWTSVDPlugin.NAMESPACE);

    /**
     * Constant for Namespace to use for this plugin
     */
    public static final String NAMESPACE = "DWTSVD";

    /** Marker that identifies this plugin's signature/watermark structures. */
    private static final String SIG_MARKER = "WSVD";

    /** Side of the square block used for the SVD (in LL sub-band pixels). */
    private static final int BLOCK = 8;

    /** DWT configuration (matches the conventions used by the other watermarking plugins). */
    private static final int DWT_FILTER_ID = 1;

    private static final int DWT_METHOD = 2;
    private static final int DWT_LEVEL = 1;

    /** Defaults for newly generated signatures. */
    private static final int DEFAULT_PAYLOAD_BITS = 64;

    private static final int DEFAULT_PARITY_BYTES = 8;

    /**
     * Default <em>relative</em> QIM step. The actual quantization step for a block is this value times a global
     * reference &mu; (the mean largest-singular-value over all blocks). Making the step proportional to a gain-linear
     * reference rather than an absolute constant is what gives the watermark robustness to global brightness/contrast
     * (valumetric) scaling: a global gain multiplies every singular value <em>and</em> &mu; by the same factor, so the
     * quantizer bins scale with the signal and the embedded parity is preserved (Rational Dither Modulation idea).
     * Chosen so that the effective step (&mu; is typically a few hundred) keeps {@code step/2} well above the ~16-unit
     * round-trip/JPEG perturbation while staying imperceptible (~40 dB PSNR). May be overridden via the
     * {@code dwtsvd.strength} system property for tuning.
     */
    private static final double DEFAULT_STRENGTH = 0.035;

    /**
     * Default constructor
     */
    public DWTSVDPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.DWTSVDPluginLabels");
        DWTSVDErrors.init();
    }

    @Override
    public String getName() {
        return "DWTSVD";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }

    // ------------------------------------------------------------------
    // Embedding
    // ------------------------------------------------------------------

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_NO_COVER_FILE);
        }

        PixelImage image = ImageCodecRegistry.get().decode(cover, coverFileName);
        int cols = image.getWidth();
        int rows = image.getHeight();

        List<int[][]> yuv = YuvImageUtil.getYuvFromImage(image);
        int[][] luminance = yuv.get(0);

        Signature sig = new Signature(msg);
        int[] codeBits = buildCodeBits(sig);

        DWT dwt = new DWT(cols, rows, DWT_FILTER_ID, DWT_LEVEL, DWT_METHOD);
        ImageTree tree = dwt.forwardDWT(luminance);
        Image ll = tree.getCoarse().getImage();

        embedIntoLL(ll, sig, codeBits);

        dwt.inverseDWT(tree, luminance);
        yuv.set(0, luminance);
        YuvImageUtil.applyYuvToImage(yuv, image);

        return ImageCodecRegistry.get().encode(image, stegoFileName);
    }

    private void embedIntoLL(Image ll, Signature sig, int[] codeBits) throws OpenStegoException {
        int blocksW = ll.getWidth() / BLOCK;
        int blocksH = ll.getHeight() / BLOCK;
        int numBlocks = blocksW * blocksH;
        if (numBlocks < codeBits.length) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }

        // Pass 1: derive the global reference mu = mean(S0). The QIM step is scaled by mu so that a global
        // brightness/contrast gain (which multiplies every S0 and mu alike) leaves the parity intact. Keep only the
        // scalar largest singular value per block - not the whole decomposition - so a large cover does not pin one
        // Svd object per block (tens of thousands on a multi-megapixel photo) in memory at once.
        double sum = 0.0;
        for (int br = 0; br < blocksH; br++) {
            for (int bc = 0; bc < blocksW; bc++) {
                sum += Svd.largestSingularValue(getBlock(ll, br, bc));
            }
        }
        double mu = sum / numBlocks;
        if (mu < 1e-6) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }
        double step = sig.strength * mu;

        // Pass 2: recompute each block's SVD, quantize its largest singular value to embed the code bit, and rebuild
        // the block. Each block is assigned a code-bit index from a password-keyed hash of its ABSOLUTE (row, col).
        // Unlike a global permutation over the block count, this mapping does not change when the image is later
        // cropped/resized: blocks that survive a crop still carry the same code index, which (together with the
        // alignment search in extractData) is what lets the watermark survive small crops/translations (#69).
        for (int br = 0; br < blocksH; br++) {
            for (int bc = 0; bc < blocksW; bc++) {
                int idx = codeIndexForBlock(sig.seed, br, bc, codeBits.length);
                int bit = codeBits[idx];
                Svd svd = new Svd(getBlock(ll, br, bc));
                double newS0 = quantize(svd.getSingularValue(0), step, bit);
                svd.setSingularValue(0, newS0);
                putBlock(ll, br, bc, svd.reconstruct());
            }
        }
    }

    /**
     * Deterministic, password-keyed, <em>position-absolute</em> mapping from a block's (row, col) to a code-bit
     * index. SplitMix64-style avalanche gives a near-uniform spread of code indices across blocks (so each bit
     * is repetition-tiled many times) while depending only on the absolute coordinates - not on the image size -
     * so a crop that removes border blocks leaves the remaining assignments intact.
     */
    private static int codeIndexForBlock(long seed, int row, int col, int codeLen) {
        long h = seed + 0x9E3779B97F4A7C15L * (row + 1) + 0xC2B2AE3D27D4EB4FL * (col + 1);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (int) Math.floorMod(h, (long) codeLen);
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    /** Max number of LL blocks a crop may have removed from the top/left that the alignment search will recover. */
    private static final int MAX_BLOCK_OFFSET = 4;

    /** Code-bit match fraction at which the zero-offset (uncropped) alignment is accepted without searching. */
    private static final double FASTPATH_MATCH = 0.92;

    /**
     * Minimum code-bit match a <em>searched</em> (non-baseline) alignment must reach to be trusted. The search
     * maximises the match over many candidate offsets, so by chance alone the best of them sits a little above
     * 50%; this floor stays well clear of that, so an absent/wrong watermark falls back to the zero-offset
     * decode (~50%, i.e. correlation ~0) instead of a spurious high reading.
     */
    private static final double SEARCH_ACCEPT_MATCH = 0.70;

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        Signature sig = new Signature(origSigData);

        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int cols = image.getWidth();
        int rows = image.getHeight();
        int[][] luminance = YuvImageUtil.getYuvFromImage(image).get(0);

        DWT dwt = new DWT(cols, rows, DWT_FILTER_ID, DWT_LEVEL, DWT_METHOD);
        ImageTree tree = dwt.forwardDWT(luminance);
        Image ll = tree.getCoarse().getImage();

        int codeLen = codeBitLength(sig);
        if ((ll.getWidth() / BLOCK) * (ll.getHeight() / BLOCK) < codeLen) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }

        // The expected code bits (RS-encoded original payload) are known from the signature, so the best
        // grid alignment is the one whose decoded bits match them most closely. Try the natural (uncropped)
        // alignment first; if it is already a strong match, skip the search entirely so the common case (and
        // the JPEG/noise/brightness robustness) is unchanged. Otherwise search small grid-phase and
        // block-origin offsets to resynchronise after a crop/translation (#69).
        int[] expected = buildCodeBits(sig);
        int[] baseline = decodeCodeBits(ll, sig, 0, 0, 0, 0, codeLen);
        int baselineScore = matchCount(baseline, expected);

        int[] best = baseline;
        if ((double) baselineScore / codeLen < FASTPATH_MATCH) {
            int[] searchBest = null;
            int searchBestScore = -1;
            for (int phaseY = 0; phaseY < BLOCK; phaseY++) {
                for (int phaseX = 0; phaseX < BLOCK; phaseX++) {
                    // The SVD grid (and hence the QIM step) depends only on the grid PHASE, not on the block-origin
                    // offset, which merely shifts the code-index mapping. So decompose each phase's grid once here and
                    // reuse it across all (offR, offC) candidates - the block-origin search then costs only cheap
                    // re-votes instead of re-running an SVD over every block 25x (the dominant cost when verifying an
                    // unwatermarked image, which always falls through to this full search).
                    double[][] s0 = computeS0Grid(ll, phaseY, phaseX);
                    double step = stepFor(s0, sig.strength);
                    for (int offR = 0; offR <= MAX_BLOCK_OFFSET; offR++) {
                        for (int offC = 0; offC <= MAX_BLOCK_OFFSET; offC++) {
                            if (phaseY == 0 && phaseX == 0 && offR == 0 && offC == 0) {
                                continue; // already evaluated as the baseline
                            }
                            int[] cand = voteCodeBits(s0, step, sig.seed, offR, offC, codeLen);
                            int score = matchCount(cand, expected);
                            if (score > searchBestScore) {
                                searchBestScore = score;
                                searchBest = cand;
                            }
                        }
                    }
                }
            }
            // Only trust a resynchronised alignment if it beats the baseline AND clears the chance floor; this
            // keeps a small crop recoverable without turning the multi-offset search into a false-positive source.
            if (searchBest != null
                    && searchBestScore > baselineScore
                    && (double) searchBestScore / codeLen >= SEARCH_ACCEPT_MATCH) {
                best = searchBest;
            }
        }

        ReedSolomon rs = new ReedSolomon(sig.parityBytes);
        byte[] payload = rs.decode(bitsToBytes(best));

        // Wrap with marker so getWatermarkCorrelation can validate and compare.
        byte[] out = new byte[SIG_MARKER.length() + payload.length];
        System.arraycopy(SIG_MARKER.getBytes(StandardCharsets.UTF_8), 0, out, 0, SIG_MARKER.length());
        System.arraycopy(payload, 0, out, SIG_MARKER.length(), payload.length);
        return out;
    }

    /**
     * Decodes the code bits for one candidate grid alignment: a sub-block grid phase {@code (phaseY, phaseX)}
     * in LL pixels and a block-origin offset {@code (offR, offC)} describing how many whole blocks a crop is
     * assumed to have removed from the top/left. Each surviving block votes for its absolutely-addressed code
     * index; the majority over all repetitions gives the bit.
     */
    private int[] decodeCodeBits(Image ll, Signature sig, int phaseY, int phaseX, int offR, int offC, int codeLen) {
        double[][] s0 = computeS0Grid(ll, phaseY, phaseX);
        double step = stepFor(s0, sig.strength);
        return voteCodeBits(s0, step, sig.seed, offR, offC, codeLen);
    }

    /**
     * Decomposes every 8x8 block at grid phase {@code (phaseY, phaseX)} and returns its largest singular value. This is
     * the expensive part of a decode (one SVD per block) and depends only on the phase, so the alignment search
     * computes it once per phase and reuses it across all block-origin offsets.
     */
    private static double[][] computeS0Grid(Image ll, int phaseY, int phaseX) {
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
    private static double stepFor(double[][] s0, double strength) {
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
     * Recovers the code bits from a precomputed singular-value grid: each block votes (by the parity of its QIM-decoded
     * largest singular value) for the absolutely-addressed code index it carries, shifted by the block-origin offset
     * {@code (offR, offC)}; the majority over all repetitions gives each bit.
     */
    private static int[] voteCodeBits(double[][] s0, double step, long seed, int offR, int offC, int codeLen) {
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

    private static int matchCount(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Correlation / verification
    // ------------------------------------------------------------------

    @Override
    public double getWatermarkCorrelation(byte[] origSigData, byte[] watermarkData) throws OpenStegoException {
        Signature sig = new Signature(origSigData);
        byte[] orig = sig.payload;

        int markLen = SIG_MARKER.length();
        if (watermarkData == null || watermarkData.length < markLen + orig.length) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_SIG_NOT_VALID);
        }
        for (int i = 0; i < markLen; i++) {
            if (watermarkData[i] != SIG_MARKER.charAt(i)) {
                throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_SIG_NOT_VALID);
            }
        }

        int matches = 0;
        int totalBits = orig.length * 8;
        for (int i = 0; i < orig.length; i++) {
            int diff = (orig[i] ^ watermarkData[markLen + i]) & 0xff;
            matches += 8 - Integer.bitCount(diff);
        }

        // Map the raw bit-match fraction [0.5, 1.0] to a correlation in [0, 1] so that an absent or wrong watermark
        // (≈ 50% random match) reads as ~0 and a recovered watermark reads as ~1.
        double frac = (double) matches / (double) totalBits;
        double correlation = 2.0 * frac - 1.0;
        return Math.max(0.0, Math.min(1.0, correlation));
    }

    // ------------------------------------------------------------------
    // Signature generation
    // ------------------------------------------------------------------

    @Override
    public byte[] generateSignature() throws OpenStegoException {
        Random rand = new Random(StringUtil.passwordHash(this.config.getPassword()));
        return new Signature(rand).getSigData();
    }

    // ------------------------------------------------------------------
    // Watermark coding helpers
    // ------------------------------------------------------------------

    private static int codeBitLength(Signature sig) {
        return (sig.payload.length + sig.parityBytes) * 8;
    }

    private int[] buildCodeBits(Signature sig) {
        ReedSolomon rs = new ReedSolomon(sig.parityBytes);
        byte[] code = rs.encode(sig.payload);
        return bytesToBits(code);
    }

    private static int[] bytesToBits(byte[] bytes) {
        int[] bits = new int[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            for (int j = 0; j < 8; j++) {
                bits[i * 8 + j] = (b >> (7 - j)) & 1;
            }
        }
        return bits;
    }

    private static byte[] bitsToBytes(int[] bits) {
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

    /** QIM embed: return the nearest multiple of {@code step} whose index parity equals {@code bit}. */
    private static double quantize(double value, double step, int bit) {
        long q = Math.round(value / step);
        if ((q & 1L) != (bit & 1)) {
            double lower = (q - 1) * step;
            double upper = (q + 1) * step;
            q += (Math.abs(value - lower) <= Math.abs(value - upper)) ? -1 : 1;
        }
        return q * step;
    }

    /** QIM decode: the parity of the nearest quantizer index gives the bit. */
    private static int decodeBit(double value, double step) {
        long q = Math.round(value / step);
        return (int) (q & 1L);
    }

    private static double[][] getBlock(Image ll, int br, int bc) {
        return getBlockAt(ll, br * BLOCK, bc * BLOCK);
    }

    /** Reads an 8x8 block whose top-left corner is at LL pixel ({@code originY}, {@code originX}). */
    private static double[][] getBlockAt(Image ll, int originY, int originX) {
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

    private static void putBlock(Image ll, int br, int bc, double[][] blk) {
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

    // ------------------------------------------------------------------
    // Signature data structure
    // ------------------------------------------------------------------

    /**
     * Holds the watermark payload and the parameters needed to embed/recover it. Serialized to the signature file so
     * that verification (checkMark) needs only the signature, not the password or the original cover.
     */
    private static class Signature {
        private final byte[] sig = SIG_MARKER.getBytes(StandardCharsets.UTF_8);
        private int payloadBits = DEFAULT_PAYLOAD_BITS;
        private int parityBytes = DEFAULT_PARITY_BYTES;
        private double strength = DEFAULT_STRENGTH;
        private long seed;
        private byte[] payload;

        /** Generate a fresh signature with a random payload and scrambling seed. */
        Signature(Random rand) {
            // Allow the embedding strength to be overridden (used for benchmarking/tuning); defaults otherwise.
            this.strength =
                    Double.parseDouble(System.getProperty("dwtsvd.strength", Double.toString(DEFAULT_STRENGTH)));
            this.seed = rand.nextLong();
            this.payload = new byte[this.payloadBits / 8];
            rand.nextBytes(this.payload);
        }

        /** Parse an existing signature. */
        Signature(byte[] sigData) throws OpenStegoException {
            if (sigData == null) {
                throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_SIG_NOT_VALID);
            }
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(sigData))) {
                byte[] inputSig = new byte[this.sig.length];
                int n = ois.read(inputSig, 0, this.sig.length);
                if (n == -1 || !(new String(this.sig)).equals(new String(inputSig))) {
                    throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_SIG_NOT_VALID);
                }
                this.payloadBits = ois.readInt();
                this.parityBytes = ois.readInt();
                this.strength = ois.readDouble();
                this.seed = ois.readLong();
                this.payload = new byte[this.payloadBits / 8];
                for (int i = 0; i < this.payload.length; i++) {
                    this.payload[i] = ois.readByte();
                }
            } catch (IOException ioEx) {
                throw new OpenStegoException(ioEx);
            }
        }

        byte[] getSigData() throws OpenStegoException {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.write(this.sig);
                oos.writeInt(this.payloadBits);
                oos.writeInt(this.parityBytes);
                oos.writeDouble(this.strength);
                oos.writeLong(this.seed);
                oos.write(this.payload);
                oos.flush();
                return baos.toByteArray();
            } catch (IOException ioEx) {
                throw new OpenStegoException(ioEx);
            }
        }
    }
}
