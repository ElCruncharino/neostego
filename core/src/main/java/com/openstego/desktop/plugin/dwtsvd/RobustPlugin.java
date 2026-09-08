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
 * additive noise, valumetric (brightness/contrast gain) scaling, a moderate geometric resize, and small
 * crops/translations -- the same QIM-on-largest-singular-value channel {@link DWTSVDPlugin} uses for
 * watermarking (see {@link SvdQimChannel}), exposed here as an actual byte channel instead of a fixed
 * watermark checked by correlation. Verified with byte-exact recovery on a 12MP cover (including a real,
 * unretouched photograph, not just an evenly-textured synthetic one -- see {@code isRisky} below) down to
 * JPEG quality 20 with no resize -- well past the QF~62 threshold a competing tool advertises surviving --
 * and, separately, through a resize to around 90% of the original size followed by that same recompression
 * at a milder quality, without ever encoding a synchronization template.
 * <p>
 * Fixed-size payload (like {@code GanStegPlugin} and the JpegUniward shadow message): the framed message
 * ({@link LSBDataHeader} + payload) is padded to {@link #RS_MESSAGE_BYTES} and Reed-Solomon encoded as
 * one block, so the code length embedded is always the same regardless of the actual message size.
 * <p>
 * Resynchronization uses neither a separately-embedded template (a DFT peak, say) nor foreknowledge of
 * the payload (comparing against a known-expected bit pattern, as the watermark path does): a candidate
 * decode is accepted the moment it happens to be Reed-Solomon correctable, which only a genuinely aligned
 * one is, overwhelmingly, ever going to be. The payload's own error-correcting structure doubles as the
 * synchronization signal, at no extra distortion cost -- and it is applied twice over, under two
 * independent, nested mechanisms layered on the very same blocks (see
 * {@link SvdQimChannel#embedCodeBitsDualAddress}): position-absolute per-block QIM, searched over an
 * integer phase/offset grid, for a crop or translation; and low-frequency 2-D DCT coefficients of the
 * whole block-energy grid, needing no search since that layer has no phase to lose, for a rescale.
 * <p>
 * Known limits, stated plainly rather than glossed over. First, the resize margin is narrow -- reliable
 * only in roughly a 90-95% window of the original size, not yet a wide, general-purpose resize tolerance
 * -- because the near-boundary block exclusion real photos need (see {@code isRisky} below) shrinks the
 * DCT layer's own redundancy budget along with the QIM layer's, and widening that margin back out needs
 * further tuning of its own, not attempted here. Past that window the resample destroys more of the
 * block-energy grid's low-frequency content than the DCT layer's reduced redundancy can outvote -- a
 * fundamentally different failure mode than a bad synchronization guess, and not fixed by searching
 * harder. Second, a resize <em>combined with</em> a crop (as opposed to either alone) is not covered at
 * all -- the DCT layer has no phase/offset search to recover the crop's translation. Third, a cover whose
 * content is both extreme (spanning the full 0..255 range) <em>and</em> highly locally varied can still
 * fail a clean round trip even after {@link #STRENGTH_ESCALATION}'s highest rung -- verified against a
 * real, unretouched photograph with large areas of deep shadow, which now round-trips correctly, but not
 * yet stress-tested against a wider range of such content.
 */
public class RobustPlugin extends DHImagePluginTemplate<OpenStegoConfig> {

    public static final String NAMESPACE = "Robust";

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /** Framed-message budget (header + payload) per fixed Reed-Solomon block. */
    private static final int RS_MESSAGE_BYTES = 96;

    private static final int RS_PARITY_BYTES = 32;
    private static final int RS_BLOCK_BYTES = RS_MESSAGE_BYTES + RS_PARITY_BYTES;
    private static final int RS_BLOCK_BITS = RS_BLOCK_BYTES * 8;

    /**
     * Default relative QIM step; see {@link DWTSVDPlugin}'s own constant of the same purpose for the
     * general rationale. Deliberately smaller than that one: {@link SvdQimChannel#embedCodeBitsDualAddress}
     * layers a second, DCT-domain signal on top of this one, and needs this step small relative to that
     * layer's own step for the nested quantizer to keep both signals intact.
     */
    private static final double DEFAULT_STRENGTH = 0.02;

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

        long seed = StringUtil.passwordHash(this.config.getPassword());

        // Escalating strength: most covers round-trip cleanly at the base strength on the first try (see
        // STRENGTH_ESCALATION below for why some don't). Each attempt re-decodes the cover fresh and
        // re-embeds from scratch -- reusing a partially-embedded LL band across attempts would compound
        // instead of retrying -- and verifies in-memory (no real codec round trip needed; PNG is lossless,
        // so the only loss between embed and this check is the inverse-DWT's own pixel clamp, which is
        // exactly what a higher strength is trying to survive).
        double strength = strength();
        for (int attempt = 0; ; attempt++) {
            PixelImage image = ImageCodecRegistry.get().decode(cover, coverFileName);
            int cols = image.getWidth();
            int rows = image.getHeight();
            DwtSvdTransform transform = new DwtSvdTransform(cols, rows);
            Image[] bands = transform.forward(DwtSvdTransform.pixelSource(image), true);
            Image ll = bands[0];

            SvdQimChannel.embedCodeBitsDualAddress(
                    ll, codeBits, seed, strength, this::reportProgress, NAMESPACE, RobustErrors.ERR_FILE_TOO_SMALL);
            transform.inverse(bands, DwtSvdTransform.pixelSink(image));

            boolean lastAttempt = attempt == STRENGTH_ESCALATION.length - 1;
            if (lastAttempt || cleanRoundTripVerifies(image, cols, rows, strength, seed)) {
                return ImageCodecRegistry.get().encode(image, stegoFileName);
            }
            strength = STRENGTH_ESCALATION[attempt + 1];
        }
    }

    /**
     * Successive embed strengths to try until a clean (unattacked) round trip verifies -- see
     * {@link #embedData}. A block whose own signal is small relative to the embedding step gets excluded
     * rather than corrupted (see {@link SvdQimChannel#embedCodeBitsDualAddress}'s {@code isRisky}), which
     * handles most covers at the base strength; a cover with a very large fraction of such blocks (a real
     * photo with deep shadows or blown highlights, which a synthetic evenly-textured test cover never has)
     * can still fall short of a full Reed-Solomon block's worth of confidently-embedded bits and needs a
     * strength high enough to pull more blocks out of the "too risky" zone. Escalating only when needed,
     * rather than always embedding at the highest strength, keeps ordinary covers at the base strength's
     * better visual quality and resize resilience (a larger step also means a larger nested-quantizer gap
     * the DCT resize layer needs -- see {@link SvdQimChannel}'s {@code DCT_STRENGTH}).
     */
    private static final double[] STRENGTH_ESCALATION = {DEFAULT_STRENGTH, 0.04, 0.07, 0.1, 0.15, 0.2};

    private boolean cleanRoundTripVerifies(PixelImage stegoImage, int cols, int rows, double strength, long seed)
            throws OpenStegoException {
        DwtSvdTransform verify = new DwtSvdTransform(cols, rows);
        Image ll = verify.forward(DwtSvdTransform.pixelSource(stegoImage), false)[0];
        double[][] s0 = SvdQimChannel.computeS0Grid(ll, 0, 0);
        return tryDecode(s0, strength, seed, 0, 0) != null;
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

        // The QIM step embed actually used depends on which STRENGTH_ESCALATION rung embedData had to
        // reach (see its javadoc) -- nothing in the format records which one, so decode has to search
        // over the same ladder, RS-correctability identifying the right rung exactly like it identifies
        // the right crop alignment. Only at this cheap, no-search baseline stage: multiplying this search
        // into the expensive phase/offset crop search below as well would multiply its already-large
        // hypothesis count sixfold, and with that many candidates a wrong (phase, offset, strength)
        // triple landing on something that merely looks Reed-Solomon-correctable stops being negligible.
        // A cover that both needed escalation AND was then cropped is not covered by this decode.
        double[][] baselineS0 = SvdQimChannel.computeS0Grid(ll, 0, 0);
        for (double strength : STRENGTH_ESCALATION) {
            byte[] baseline = tryDecode(baselineS0, strength, seed, 0, 0);
            if (baseline != null) {
                return baseline;
            }
        }

        // Cheap (no phase/offset search) before the expensive crop search below: a resize with no crop
        // resolves on the first try, since the DCT scheme has no phase to search over.
        byte[] resized = tryDecodeDct(baselineS0, seed);
        if (resized != null) {
            return resized;
        }

        double strength = strength();
        for (int phaseY = 0; phaseY < SvdQimChannel.BLOCK; phaseY++) {
            for (int phaseX = 0; phaseX < SvdQimChannel.BLOCK; phaseX++) {
                reportProgress(
                        (phaseY * SvdQimChannel.BLOCK + phaseX + 1.0) / (SvdQimChannel.BLOCK * SvdQimChannel.BLOCK));
                double[][] s0 = SvdQimChannel.computeS0Grid(ll, phaseY, phaseX);
                for (int offR = 0; offR <= MAX_BLOCK_OFFSET; offR++) {
                    for (int offC = 0; offC <= MAX_BLOCK_OFFSET; offC++) {
                        if (phaseY == 0 && phaseX == 0 && offR == 0 && offC == 0) {
                            continue; // already evaluated as the baseline
                        }
                        byte[] candidate = tryDecode(s0, strength, seed, offR, offC);
                        if (candidate != null) {
                            return candidate;
                        }
                    }
                }
            }
        }
        throw new OpenStegoException(null, NAMESPACE, RobustErrors.ERR_DECODE_FAILED);
    }

    /**
     * Soft-decision votes, RS-decodes, and returns the payload iff the block is both RS-correctable and
     * parses as a real {@link LSBDataHeader} -- else {@code null}. The header check matters here more
     * than it would with a single fixed strength: searching {@link #STRENGTH_ESCALATION} tries several
     * unrelated interpretations of the same noisy grid, and Reed-Solomon's own false-accept rate, small
     * for any one guess, stops being negligible once several are tried against real (not uniformly
     * random) image content. A wrong guess's "corrected" bytes essentially never happen to also parse as
     * a valid header, so this catches what RS-correctability alone let through.
     */
    private byte[] tryDecode(double[][] s0, double strength, long seed, int offR, int offC) {
        int[] codeBits = SvdQimChannel.voteCodeBitsWeighted(s0, strength, seed, offR, offC, RS_BLOCK_BITS);
        return decodeIfValid(SvdQimChannel.bitsToBytes(codeBits));
    }

    /** As {@link #tryDecode}, but against the DCT scheme that survives a resize. */
    private byte[] tryDecodeDct(double[][] s0, long seed) {
        int[] codeBits = SvdQimChannel.voteCodeBitsDct(s0, seed, RS_BLOCK_BITS);
        return decodeIfValid(SvdQimChannel.bitsToBytes(codeBits));
    }

    private byte[] decodeIfValid(byte[] block) {
        if (!this.reedSolomon.isCorrectable(block)) {
            return null;
        }
        byte[] decoded = this.reedSolomon.decode(block);
        try {
            parseHeader(decoded);
            return decoded;
        } catch (OpenStegoException ex) {
            return null; // RS-correctable by chance, but not a real header: a false accept
        }
    }

    /** Fixed capacity budget regardless of cover size, like {@code GanStegPlugin} and the shadow message. */
    @Override
    public int getMaxDataLength(int width, int height) {
        int headerSize = new LSBDataHeader(0, 1, null, getConfig()).getHeaderSize();
        return Math.max(0, RS_MESSAGE_BYTES - headerSize);
    }
}
