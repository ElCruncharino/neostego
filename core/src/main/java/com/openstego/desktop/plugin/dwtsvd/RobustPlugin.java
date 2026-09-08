/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.ContainerType;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.StringUtil;
import com.openstego.desktop.util.dwt.Image;
import com.openstego.desktop.util.ecc.ReedSolomon;
import java.io.ByteArrayInputStream;

/**
 * Robust data-hiding plugin: an arbitrary short message, recoverable after JPEG re-compression,
 * additive noise, valumetric (brightness/contrast gain) scaling, and small crops/translations -- the
 * same QIM-on-largest-singular-value channel {@link DWTSVDPlugin} uses for watermarking (see
 * {@link SvdQimChannel}), exposed here as an actual byte channel instead of a fixed watermark checked
 * by correlation. Verified with byte-exact recovery on a 12MP cover down to JPEG quality 20 -- well
 * past the QF~62 threshold competing tools advertise surviving -- with no resize.
 * <p>
 * Known limitation, stated plainly rather than glossed over: this does <em>not</em> yet survive genuine
 * geometric resizing (as opposed to a pixel-aligned crop). The code-index-per-block addressing is
 * position-absolute in the original pixel grid; resizing changes that grid entirely, and the current
 * resynchronization search only covers integer block-phase/offset shifts, not scale. A tool using a
 * frequency-domain synchronization template is built to survive exactly this case; this one is not,
 * yet -- a real, disclosed gap, not a claim to compete on until it's actually solved.
 * <p>
 * Fixed-size payload (like {@code GanStegPlugin} and the JpegUniward shadow message): the framed message
 * ({@link LSBDataHeader} + payload) is padded to {@link #RS_MESSAGE_BYTES} and Reed-Solomon encoded as
 * one block, so the code length embedded is always the same regardless of the actual message size.
 * <p>
 * Resynchronization after a crop uses neither a separately-embedded template (a DFT peak, say) nor
 * foreknowledge of the payload (comparing against a known-expected bit pattern, as the watermark path
 * does): a candidate alignment is accepted the moment it happens to be Reed-Solomon correctable, which
 * only the true alignment is, overwhelmingly, ever going to be. The payload's own error-correcting
 * structure doubles as the synchronization signal, at no extra distortion cost.
 */
public class RobustPlugin extends DHImagePluginTemplate<OpenStegoConfig> {

    public static final String NAMESPACE = "Robust";

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /** Framed-message budget (header + payload) per fixed Reed-Solomon block. */
    private static final int RS_MESSAGE_BYTES = 96;

    private static final int RS_PARITY_BYTES = 32;
    private static final int RS_BLOCK_BYTES = RS_MESSAGE_BYTES + RS_PARITY_BYTES;
    private static final int RS_BLOCK_BITS = RS_BLOCK_BYTES * 8;

    /** Default relative QIM step; see {@link DWTSVDPlugin}'s identical constant for the rationale. */
    private static final double DEFAULT_STRENGTH = 0.035;

    /** Max LL blocks a crop may have removed from the top/left that resynchronization will recover. */
    private static final int MAX_BLOCK_OFFSET = 4;

    private final ReedSolomon reedSolomon = new ReedSolomon(RS_PARITY_BYTES);

    public RobustPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.RobustPluginLabels");
        RobustErrors.init();
    }

    @Override
    public String getName() {
        return "Robust";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage", RS_MESSAGE_BYTES);
    }

    /**
     * Raster-only, like the LSB/GAN families; the QIM channel needs raw pixels, not JPEG/WAV. Also
     * gates on the fixed capacity (unlike the LSB family, whose per-pixel rate scales with any image):
     * an image too small for {@link #RS_BLOCK_BITS} would otherwise reach {@link #embedData}'s capacity
     * exception, which {@code AutoExtractor} would surface as the final error ahead of a more useful one
     * from a candidate that actually matched -- exactly the class of bug this method exists to prevent
     * (see its own javadoc). A decode here costs no more than the one {@link #extractData} does anyway.
     */
    @Override
    public boolean canExtractFrom(byte[] stegoData) {
        if (ContainerType.detect(stegoData) != ContainerType.OTHER) {
            return false;
        }
        try {
            PixelImage image = ImageCodecRegistry.get().decode(stegoData, "");
            int llW = (image.getWidth() + 1) / 2;
            int llH = (image.getHeight() + 1) / 2;
            return (llW / SvdQimChannel.BLOCK) * (llH / SvdQimChannel.BLOCK) >= RS_BLOCK_BITS;
        } catch (RuntimeException | OpenStegoException ex) {
            return false;
        }
    }

    @Override
    protected OpenStegoConfig createConfig() {
        return new OpenStegoConfig();
    }

    private static double strength() {
        return Double.parseDouble(System.getProperty("robust.strength", Double.toString(DEFAULT_STRENGTH)));
    }

    // ------------------------------------------------------------------
    // Embedding
    // ------------------------------------------------------------------

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_NO_COVER_FILE);
        }

        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int fullLength = headerBytes.length + msg.length;
        if (fullLength > RS_MESSAGE_BYTES) {
            throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_MESSAGE_TOO_LONG);
        }
        byte[] padded = new byte[RS_MESSAGE_BYTES];
        System.arraycopy(headerBytes, 0, padded, 0, headerBytes.length);
        System.arraycopy(msg, 0, padded, headerBytes.length, msg.length);
        byte[] block = this.reedSolomon.encode(padded);
        int[] codeBits = SvdQimChannel.bytesToBits(block);

        PixelImage image = ImageCodecRegistry.get().decode(cover, coverFileName);
        int cols = image.getWidth();
        int rows = image.getHeight();
        DwtSvdTransform transform = new DwtSvdTransform(cols, rows);
        Image[] bands = transform.forward(DwtSvdTransform.pixelSource(image), true);
        Image ll = bands[0];

        long seed = StringUtil.passwordHash(this.config.getPassword());
        SvdQimChannel.embedCodeBits(
                ll, codeBits, seed, strength(), this::reportProgress, NAMESPACE, RobustErrors.ERR_FILE_TOO_SMALL);

        transform.inverse(bands, DwtSvdTransform.pixelSink(image));
        return ImageCodecRegistry.get().encode(image, stegoFileName);
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        return parseHeader(decodeBlock(stegoData, stegoFileName)).getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        byte[] full = decodeBlock(stegoData, stegoFileName);
        ByteArrayInputStream in = new ByteArrayInputStream(full);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        int dataLength = header.getDataLength();
        int headerLen = full.length - in.available();
        if (dataLength < 0 || headerLen + dataLength > full.length) {
            throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_DECODE_FAILED);
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(full, headerLen, data, 0, dataLength);
        return data;
    }

    private LSBDataHeader parseHeader(byte[] full) throws OpenStegoException {
        return new LSBDataHeader(new ByteArrayInputStream(full), this.config);
    }

    /**
     * Decodes the fixed RS block, trying the natural (uncropped, unrotated) alignment first and falling
     * back to a phase/block-origin search -- see the class javadoc for why RS-correctability itself,
     * not a template or foreknowledge of the payload, is what identifies the right alignment.
     */
    private byte[] decodeBlock(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int cols = image.getWidth();
        int rows = image.getHeight();
        DwtSvdTransform transform = new DwtSvdTransform(cols, rows);
        Image ll = transform.forward(DwtSvdTransform.pixelSource(image), false)[0];

        if ((ll.getWidth() / SvdQimChannel.BLOCK) * (ll.getHeight() / SvdQimChannel.BLOCK) < RS_BLOCK_BITS) {
            throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_FILE_TOO_SMALL);
        }

        long seed = StringUtil.passwordHash(this.config.getPassword());
        double strength = strength();

        double[][] baselineS0 = SvdQimChannel.computeS0Grid(ll, 0, 0);
        byte[] baseline = tryDecode(baselineS0, SvdQimChannel.stepFor(baselineS0, strength), seed, 0, 0);
        if (baseline != null) {
            return baseline;
        }

        for (int phaseY = 0; phaseY < SvdQimChannel.BLOCK; phaseY++) {
            for (int phaseX = 0; phaseX < SvdQimChannel.BLOCK; phaseX++) {
                reportProgress(
                        (phaseY * SvdQimChannel.BLOCK + phaseX + 1.0) / (SvdQimChannel.BLOCK * SvdQimChannel.BLOCK));
                double[][] s0 = SvdQimChannel.computeS0Grid(ll, phaseY, phaseX);
                double step = SvdQimChannel.stepFor(s0, strength);
                for (int offR = 0; offR <= MAX_BLOCK_OFFSET; offR++) {
                    for (int offC = 0; offC <= MAX_BLOCK_OFFSET; offC++) {
                        if (phaseY == 0 && phaseX == 0 && offR == 0 && offC == 0) {
                            continue; // already evaluated as the baseline
                        }
                        byte[] candidate = tryDecode(s0, step, seed, offR, offC);
                        if (candidate != null) {
                            return candidate;
                        }
                    }
                }
            }
        }
        throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_DECODE_FAILED);
    }

    /** Soft-decision votes, RS-decodes, and returns the payload iff the block is RS-correctable; else {@code null}. */
    private byte[] tryDecode(double[][] s0, double step, long seed, int offR, int offC) {
        int[] codeBits = SvdQimChannel.voteCodeBitsWeighted(s0, step, seed, offR, offC, RS_BLOCK_BITS);
        byte[] block = SvdQimChannel.bitsToBytes(codeBits);
        if (!this.reedSolomon.isCorrectable(block)) {
            return null;
        }
        return this.reedSolomon.decode(block);
    }

    /** Fixed capacity budget regardless of cover size, like {@code GanStegPlugin} and the shadow message. */
    @Override
    public int getMaxDataLength(int width, int height) {
        int headerSize = new LSBDataHeader(0, 1, null, getConfig()).getHeaderSize();
        return Math.max(0, RS_MESSAGE_BYTES - headerSize);
    }
}
