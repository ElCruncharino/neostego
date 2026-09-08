/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 *
 * The bundled network weights (onnx/steganogan_dense_{encoder,decoder}.onnx) are exported from the
 * "dense" architecture pretrained checkpoint published by DAI-Lab/SteganoGAN (MIT licence); only the
 * weights are reused, this Java embed/extract/error-correction pipeline around them is new.
 */

package com.openstego.desktop.plugin.gan;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.ecc.ReedSolomon;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Experimental GAN-based steganography plugin, wrapping a pretrained SteganoGAN "dense" encoder/decoder
 * (run locally through ONNX Runtime, see {@link OnnxSteganoGanCodec}) instead of a hand-designed
 * embedding rule. The network was trained end-to-end against a steganalysis discriminator, so the
 * pixel changes it makes are shaped to be statistically harder to detect than a fixed rule like LSB, at
 * the cost of a non-trivial raw bit error rate (network output is a learned approximation, not an exact
 * channel).
 * <p>
 * That raw error rate (empirically ~18-20% per bit, which compounds to a large majority of individual
 * bytes being wrong) is far beyond what a single Reed-Solomon block can correct. So this plugin treats
 * the network as a noisy channel and layers two things on top of it, the same way a real-world radio
 * link would: the framed message (the standard {@link LSBDataHeader} plus payload) is Reed-Solomon
 * encoded as ONE fixed-size block, then that block is tiled redundantly across the entire embeddable
 * capacity of the cover; on extraction every tile copy is decoded independently and only the copies
 * that pass {@link ReedSolomon#isCorrectable} are kept, with the most common result among those winning
 * a majority vote. A larger cover means more tile repeats and a better chance that at least one copy
 * landed on a favorable (low-error) region, so &mdash; unlike the other plugins here &mdash; a bigger
 * image mostly buys reliability, not capacity: the maximum message size is a small fixed budget
 * ({@link #RS_MESSAGE_BYTES} bytes total, header included), regardless of cover size.
 * <p>
 * This makes it unsuitable as a bulk data channel; it is offered as a robustness/undetectability
 * research plugin for short messages, alongside the classical algorithms.
 */
public class GanStegPlugin extends DHImagePluginTemplate<GanStegConfig> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "GAN";

    /**
     * Total framed-message budget (standard header + payload) per Reed-Solomon block. Chosen small
     * enough that most of the 255-byte codeword can be spent on parity, since the network's raw error
     * rate needs heavy correction to have any chance of landing a clean tile.
     */
    private static final int RS_MESSAGE_BYTES = 64;

    /** Parity bytes for the single fixed RS block (correct up to 95 byte errors out of 255). */
    private static final int RS_PARITY_BYTES = 255 - RS_MESSAGE_BYTES;

    private static final int RS_BLOCK_BYTES = RS_MESSAGE_BYTES + RS_PARITY_BYTES;
    private static final int RS_BLOCK_BITS = RS_BLOCK_BYTES * 8;

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    private final ReedSolomon reedSolomon = new ReedSolomon(RS_PARITY_BYTES);
    private final OnnxSteganoGanCodec codec;

    // extractMsgFileName and extractData are always called back-to-back on the same stegoData array
    // (see OpenStego#extractData); caching by reference avoids paying for the ONNX decoder pass twice.
    private byte[] cachedStegoData;
    private byte[] cachedFull;

    /**
     * Default constructor.
     */
    public GanStegPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.GanStegPluginLabels");
        GanStegErrors.init();
        this.codec = new OnnxSteganoGanCodec();
    }

    @Override
    public String getName() {
        return "GAN";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    /** Raster-only, like the LSB family; the GAN network was trained on raw RGB pixels, not JPEG/WAV. */
    @Override
    public boolean canExtractFrom(byte[] stegoData) {
        return com.openstego.desktop.util.ContainerType.detect(stegoData)
                == com.openstego.desktop.util.ContainerType.OTHER;
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, GanStegErrors.ERR_COVER_REQUIRED);
        }

        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int fullLength = headerBytes.length + msg.length;
        if (fullLength > RS_MESSAGE_BYTES) {
            throw new OpenStegoException(null, NAMESPACE, GanStegErrors.ERR_MESSAGE_TOO_LONG);
        }
        byte[] padded = new byte[RS_MESSAGE_BYTES];
        System.arraycopy(headerBytes, 0, padded, 0, headerBytes.length);
        System.arraycopy(msg, 0, padded, headerBytes.length, msg.length);

        byte[] block = this.reedSolomon.encode(padded);
        boolean[] blockBits = bytesToBits(block);

        PixelImage image = ImageCodecRegistry.get().decode(cover, coverFileName);
        int width = image.getWidth();
        int height = image.getHeight();
        long capacityBits = (long) OnnxSteganoGanCodec.DATA_DEPTH * width * height;
        if (capacityBits < RS_BLOCK_BITS) {
            throw new OpenStegoException(null, NAMESPACE, GanStegErrors.ERR_IMAGE_TOO_SMALL);
        }

        boolean[] payloadBits = new boolean[(int) capacityBits];
        for (int i = 0; i < payloadBits.length; i++) {
            payloadBits[i] = blockBits[i % blockBits.length];
        }

        int[] coverRgb = toRgbArray(image, width, height);
        int[] stegoRgb = this.codec.encode(coverRgb, width, height, payloadBits);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, stegoRgb[y * width + x]);
            }
        }

        return ImageCodecRegistry.get().encode(image, stegoFileName);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        return parseHeader(extractFull(stegoData, stegoFileName)).getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        byte[] full = extractFull(stegoData, stegoFileName);
        ByteArrayInputStream in = new ByteArrayInputStream(full);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        int dataLength = header.getDataLength();
        int headerLen = full.length - in.available();
        if (dataLength < 0 || headerLen + dataLength > full.length) {
            throw new OpenStegoException(null, NAMESPACE, GanStegErrors.ERR_DECODE_FAILED);
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(full, headerLen, data, 0, dataLength);
        return data;
    }

    /**
     * Runs the decoder network, tiles the raw bit stream back into RS-block-sized candidates, keeps the
     * ones that pass {@link ReedSolomon#isCorrectable}, and returns the message that wins the majority
     * vote among them.
     */
    private byte[] extractFull(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        if (stegoData == this.cachedStegoData) {
            return this.cachedFull;
        }
        byte[] full = decodeFull(stegoData, stegoFileName);
        this.cachedStegoData = stegoData;
        this.cachedFull = full;
        return full;
    }

    private byte[] decodeFull(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] stegoRgb = toRgbArray(image, width, height);
        boolean[] rawBits = this.codec.decodeBits(stegoRgb, width, height);

        int repeats = rawBits.length / RS_BLOCK_BITS;
        Map<ByteBuffer, Integer> votes = new HashMap<>();
        for (int r = 0; r < repeats; r++) {
            byte[] candidateBlock = bitsToBytes(rawBits, r * RS_BLOCK_BITS, RS_BLOCK_BITS);
            if (this.reedSolomon.isCorrectable(candidateBlock)) {
                ByteBuffer key = ByteBuffer.wrap(this.reedSolomon.decode(candidateBlock));
                votes.merge(key, 1, Integer::sum);
            }
        }

        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey().array())
                .orElseThrow(() -> new OpenStegoException(null, NAMESPACE, GanStegErrors.ERR_DECODE_FAILED));
    }

    private LSBDataHeader parseHeader(byte[] full) throws OpenStegoException {
        return new LSBDataHeader(new ByteArrayInputStream(full), this.config);
    }

    /**
     * Fixed capacity budget regardless of cover size (see class Javadoc): a larger cover only improves
     * decode reliability via more tile repeats, it never raises the message ceiling.
     */
    @Override
    public int getMaxDataLength(int width, int height) {
        long capacityBits = (long) OnnxSteganoGanCodec.DATA_DEPTH * width * height;
        if (capacityBits < RS_BLOCK_BITS) {
            return 0;
        }
        int headerSize = new LSBDataHeader(0, 1, null, getConfig()).getHeaderSize();
        return Math.max(0, RS_MESSAGE_BYTES - headerSize);
    }

    @Override
    protected GanStegConfig createConfig() {
        return new GanStegConfig();
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage", RS_MESSAGE_BYTES);
    }

    // ---------------- helpers ----------------

    private static int[] toRgbArray(PixelImage image, int width, int height) {
        int[] rgb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rgb[y * width + x] = image.getRGB(x, y);
            }
        }
        return rgb;
    }

    private static boolean[] bytesToBits(byte[] bytes) {
        boolean[] bits = new boolean[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            for (int j = 0; j < 8; j++) {
                bits[i * 8 + j] = ((b >> (7 - j)) & 1) != 0;
            }
        }
        return bits;
    }

    private static byte[] bitsToBytes(boolean[] bits, int offset, int length) {
        byte[] out = new byte[length / 8];
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bits[offset + i * 8 + j] ? 1 : 0);
            }
            out[i] = (byte) b;
        }
        return out;
    }
}
