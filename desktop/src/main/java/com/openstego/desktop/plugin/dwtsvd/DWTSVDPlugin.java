/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.plugin.template.image.WMImagePluginTemplate;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
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
     * Default QIM step. Chosen so that {@code step/2} comfortably exceeds the ~16-unit perturbation that a YUV
     * round-trip plus JPEG re-compression induces in a block's largest singular value, while keeping the watermark
     * imperceptible (~40 dB PSNR). May be overridden via the {@code dwtsvd.strength} system property for tuning.
     */
    private static final double DEFAULT_STRENGTH = 64.0;

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
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName) throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_NO_COVER_FILE);
        }

        ImageHolder image = ImageUtil.byteArrayToImage(cover, coverFileName);
        int imgType = image.getImage().getType();
        int cols = image.getImage().getWidth();
        int rows = image.getImage().getHeight();

        List<int[][]> yuv = ImageUtil.getYuvFromImage(image.getImage());
        int[][] luminance = yuv.get(0);

        Signature sig = new Signature(msg);
        int[] codeBits = buildCodeBits(sig);

        DWT dwt = new DWT(cols, rows, DWT_FILTER_ID, DWT_LEVEL, DWT_METHOD);
        ImageTree tree = dwt.forwardDWT(luminance);
        Image ll = tree.getCoarse().getImage();

        embedIntoLL(ll, sig, codeBits);

        dwt.inverseDWT(tree, luminance);
        yuv.set(0, luminance);
        image.setImage(ImageUtil.getImageFromYuv(yuv, imgType));

        return ImageUtil.imageToByteArray(image, stegoFileName, this);
    }

    private void embedIntoLL(Image ll, Signature sig, int[] codeBits) throws OpenStegoException {
        int blocksW = ll.getWidth() / BLOCK;
        int blocksH = ll.getHeight() / BLOCK;
        int numBlocks = blocksW * blocksH;
        if (numBlocks < codeBits.length) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }

        int[] perm = blockPermutation(sig.seed, numBlocks);
        for (int rank = 0; rank < numBlocks; rank++) {
            int block = perm[rank];
            int bit = codeBits[rank % codeBits.length];
            int br = block / blocksW;
            int bc = block % blocksW;

            double[][] blk = getBlock(ll, br, bc);
            Svd svd = new Svd(blk);
            double newS0 = quantize(svd.getSingularValue(0), sig.strength, bit);
            svd.setSingularValue(0, newS0);
            putBlock(ll, br, bc, svd.reconstruct());
        }
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        Signature sig = new Signature(origSigData);

        ImageHolder image = ImageUtil.byteArrayToImage(stegoData, stegoFileName);
        int cols = image.getImage().getWidth();
        int rows = image.getImage().getHeight();
        int[][] luminance = ImageUtil.getYuvFromImage(image.getImage()).get(0);

        DWT dwt = new DWT(cols, rows, DWT_FILTER_ID, DWT_LEVEL, DWT_METHOD);
        ImageTree tree = dwt.forwardDWT(luminance);
        Image ll = tree.getCoarse().getImage();

        int codeLen = codeBitLength(sig);
        int blocksW = ll.getWidth() / BLOCK;
        int blocksH = ll.getHeight() / BLOCK;
        int numBlocks = blocksW * blocksH;
        if (numBlocks < codeLen) {
            throw new OpenStegoException(null, NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }

        int[] votesFor1 = new int[codeLen];
        int[] votesFor0 = new int[codeLen];
        int[] perm = blockPermutation(sig.seed, numBlocks);
        for (int rank = 0; rank < numBlocks; rank++) {
            int block = perm[rank];
            int br = block / blocksW;
            int bc = block % blocksW;
            double s0 = new Svd(getBlock(ll, br, bc)).getSingularValue(0);
            int bit = decodeBit(s0, sig.strength);
            int idx = rank % codeLen;
            if (bit == 1) {
                votesFor1[idx]++;
            } else {
                votesFor0[idx]++;
            }
        }

        int[] codeBits = new int[codeLen];
        for (int i = 0; i < codeLen; i++) {
            codeBits[i] = (votesFor1[i] > votesFor0[i]) ? 1 : 0;
        }

        byte[] codeBytes = bitsToBytes(codeBits);
        ReedSolomon rs = new ReedSolomon(sig.parityBytes);
        byte[] payload = rs.decode(codeBytes);

        // Wrap with marker so getWatermarkCorrelation can validate and compare.
        byte[] out = new byte[SIG_MARKER.length() + payload.length];
        System.arraycopy(SIG_MARKER.getBytes(StandardCharsets.UTF_8), 0, out, 0, SIG_MARKER.length());
        System.arraycopy(payload, 0, out, SIG_MARKER.length(), payload.length);
        return out;
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

    /** Deterministic Fisher-Yates permutation of block indices, seeded so embed and extract agree. */
    private static int[] blockPermutation(long seed, int count) {
        int[] perm = new int[count];
        for (int i = 0; i < count; i++) {
            perm[i] = i;
        }
        Random rand = new Random(seed);
        for (int i = count - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int tmp = perm[i];
            perm[i] = perm[j];
            perm[j] = tmp;
        }
        return perm;
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
        int width = ll.getWidth();
        double[] data = ll.getData();
        double[][] blk = new double[BLOCK][BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            int y = br * BLOCK + i;
            for (int j = 0; j < BLOCK; j++) {
                int x = bc * BLOCK + j;
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
            this.strength = Double.parseDouble(System.getProperty("dwtsvd.strength", Double.toString(DEFAULT_STRENGTH)));
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
            try (
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos)
            ) {
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
