/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 *
 * The F5 algorithm core (F5Embed, F5Extract, F5Permutation) and its Blake2b-based PRNG
 * (crypto/F5Random, crypto/Blake2bHash and the Digest interfaces) are lifted, near-verbatim, from
 * Secret Space Encryptor (SSE, Paranoia Works) under the MIT licence (Blake2bHash is CC0
 * public domain); both are GPLv2-compatible. The per-file SPDX/licence headers are preserved.
 */

package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.LabelUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * F5 JPEG steganography &mdash; the classic Westfeld (2001) scheme, ported from SSE. Unlike
 * NeoStego's flagship SI-UNIWARD plugin, F5 embeds directly into an <em>already-compressed</em> JPEG
 * cover: it permutes the quantized DCT coefficients with a password-seeded Blake2b PRNG and uses
 * {@code (1, n, k)} matrix encoding to hide each bit with as few coefficient changes as possible,
 * always decrementing magnitude toward zero (with shrinkage retry on coefficients that hit zero).
 * <p>
 * It is fast and well understood but, being a fixed (non-adaptive) embedder, is more detectable than
 * SI-UNIWARD &mdash; it is offered as a lightweight, broadly-compatible alternative for JPEG covers,
 * good for small payloads. Output is the re-encoded cover JPEG (its own quantization tables are
 * reused, so there is no quality knob).
 * <p>
 * The payload framing reuses NeoStego's standard {@link LSBDataHeader}, so the core's
 * compression/encryption flags and the optional file name flow through unchanged. The whole
 * coefficient array is held in memory (F5 is inherently global), which is acceptable for this
 * compatibility tier; the banded, memory-bounded design is reserved for SI-UNIWARD.
 * <p>
 * Deviation from the SSE bitstream: the SSE extractor applied a deZigZag index remap that its
 * embedder did not, relying on the two paths being fed differently-ordered arrays. We feed both paths
 * the same natural-order coefficient array and drop the remap (see {@link F5Extract}), keeping our own
 * embed/extract round-trip self-consistent. Combined with the differing header/crypto container, this
 * means cross-application steganogram interop with SSE is not a goal &mdash; only the F5 algorithm
 * layer (Blake2b PRNG + matrix encoding) is preserved.
 */
public class F5Plugin extends DHImagePluginTemplate<F5Config> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "F5";

    /** F5's 32-bit length header reserves 4 bytes of capacity before the payload. */
    private static final int F5_HEADER_BYTES = 4;

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /**
     * Default constructor.
     */
    public F5Plugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.F5PluginLabels");
        F5Errors.init();
    }

    @Override
    public String getName() {
        return "F5";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public List<String> getReadableFileExtensions() {
        return Arrays.asList("jpg", "jpeg");
    }

    @Override
    public List<String> getWritableFileExtensions() {
        return Arrays.asList("jpg", "jpeg");
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, F5Errors.ERR_COVER_REQUIRED);
        }
        JpegImage jpg = decode(cover);

        // Frame the (already compressed/encrypted by the core) payload with the standard header, so the
        // compression/encryption flags and file name round-trip exactly as for the other plugins.
        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        byte[] full = new byte[headerBytes.length + msg.length];
        System.arraycopy(headerBytes, 0, full, 0, headerBytes.length);
        System.arraycopy(msg, 0, full, headerBytes.length, msg.length);

        int[] coeff = flattenCoeffs(jpg);
        // Reject up front if the cover plainly cannot hold the payload (F5 would otherwise silently
        // truncate). f5Capacity is the n=1 default-code bound; matrix encoding only spends fewer
        // changes, so anything that passes this fits.
        if (full.length > f5Capacity(coeff)) {
            throw new OpenStegoException(null, NAMESPACE, F5Errors.IMAGE_SIZE_INSUFFICIENT);
        }

        byte[] key = keyBytes();
        try {
            F5Embed.embed(coeff, coeff.length, full, key);
        } catch (F5Extract.F5Exception ex) {
            throw new OpenStegoException(ex, NAMESPACE, F5Errors.IMAGE_SIZE_INSUFFICIENT);
        }
        unflattenCoeffs(jpg, coeff);

        return JpegCodec.encode(jpg);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        return parseHeader(extractFull(stegoData)).getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        byte[] full = extractFull(stegoData);
        // Parse the header off the front (this also sets the compression/encryption flags on config),
        // then return exactly the payload that followed it.
        ByteArrayInputStream in = new ByteArrayInputStream(full);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        int dataLength = header.getDataLength();
        int headerLen = full.length - in.available();
        if (dataLength < 0 || (long) headerLen + dataLength > full.length) {
            throw new OpenStegoException(null, NAMESPACE, F5Errors.ERR_IMAGE_DATA_READ);
        }
        byte[] data = new byte[dataLength];
        System.arraycopy(full, headerLen, data, 0, dataLength);
        return data;
    }

    /** Decodes the stego JPEG and runs F5 extraction, returning the raw framed bytes (header + payload). */
    private byte[] extractFull(byte[] stegoData) throws OpenStegoException {
        JpegImage jpg = decode(stegoData);
        int[] coeff = flattenCoeffs(jpg);
        try {
            return F5Extract.extract(coeff, coeff.length, keyBytes());
        } catch (F5Extract.F5Exception ex) {
            throw new OpenStegoException(ex, NAMESPACE, F5Errors.ERR_IMAGE_DATA_READ);
        }
    }

    /** Re-parses the standard header from already-extracted framed bytes. */
    private LSBDataHeader parseHeader(byte[] full) throws OpenStegoException {
        return new LSBDataHeader(new ByteArrayInputStream(full), this.config);
    }

    /**
     * Rough capacity estimate (bytes) from the cover dimensions, for UI capacity hints only. Without
     * the actual coefficients the nonzero-AC count is unknown, so this assumes a conservative usable
     * fraction of the AC positions; the authoritative guard is {@link #f5Capacity} in {@link #embedData}.
     */
    @Override
    public int getMaxDataLength(int width, int height) {
        int mcuCols = ceilDiv(width, 16);
        int mcuRows = ceilDiv(height, 16);
        long blocks = (long) mcuCols * mcuRows * (4 + 1 + 1); // 4:2:0: 4 Y blocks + Cb + Cr per MCU
        long acPositions = blocks * 63;
        // Assume ~10% of AC coefficients are nonzero and usable; default-code rate is ~1 bit each.
        long bits = acPositions / 10;
        return (int) Math.max(0, bits / 8 - F5_HEADER_BYTES);
    }

    @Override
    protected F5Config createConfig() {
        return new F5Config();
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }

    // ---------------- coefficient bridge ----------------

    /**
     * Flattens every component's quantized DCT coefficients into one {@code int[]}, blocks laid out
     * contiguously (64 entries each) in component/row/column raster order, so index {@code %64 == 0} is
     * a DC term &mdash; exactly what F5 expects when it skips DC. Natural (not zigzag) order is used in
     * both directions, so flatten/unflatten and the F5 embed/extract paths all agree.
     */
    private static int[] flattenCoeffs(JpegImage jpg) {
        int comps = jpg.getComponentCount();
        long total = 0;
        for (int c = 0; c < comps; c++) {
            total += (long) jpg.getBlocksWide(c) * jpg.getBlocksHigh(c) * 64;
        }
        int[] coeff = new int[(int) total];
        int idx = 0;
        for (int c = 0; c < comps; c++) {
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    short[] blk = jpg.getBlock(c, br, bc);
                    for (int k = 0; k < 64; k++) {
                        coeff[idx++] = blk[k];
                    }
                }
            }
        }
        return coeff;
    }

    /** Writes F5-modified coefficients back into the live JPEG blocks, in the same order as flatten. */
    private static void unflattenCoeffs(JpegImage jpg, int[] coeff) {
        int comps = jpg.getComponentCount();
        int idx = 0;
        for (int c = 0; c < comps; c++) {
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    short[] blk = jpg.getBlock(c, br, bc);
                    for (int k = 0; k < 64; k++) {
                        blk[k] = (short) coeff[idx++];
                    }
                }
            }
        }
    }

    /**
     * Maximum payload bytes F5 can embed in these coefficients, using the default-code ({@code n=1})
     * bound: {@code expected = large + 0.49*ones} embeddable bits, minus the 4-byte F5 length header.
     * Matrix encoding (larger {@code k}) only spends fewer changes for the same bits, so this is a safe
     * lower bound &mdash; if the payload fits this, F5 will embed it. Mirrors {@link F5Embed}'s estimate.
     */
    private static int f5Capacity(int[] coeff) {
        int ones = 0;
        int zeros = 0;
        int count = coeff.length;
        for (int x = 0; x < count; x++) {
            if (x % 64 == 0) {
                continue; // DC
            }
            if (coeff[x] == 0) {
                zeros++;
            } else if (coeff[x] == 1 || coeff[x] == -1) {
                ones++;
            }
        }
        int large = count - zeros - ones - count / 64;
        int expected = large + (int) (0.49 * ones);
        return Math.max(0, expected / 8 - F5_HEADER_BYTES);
    }

    // ---------------- helpers ----------------

    private JpegImage decode(byte[] data) throws OpenStegoException {
        try {
            return JpegCodec.decode(data);
        } catch (IOException ex) {
            throw new OpenStegoException(ex, NAMESPACE, F5Errors.ERR_JPEG);
        }
    }

    /**
     * The F5 PRNG key derived from the password. F5 always needs a key to seed its permutation and bit
     * pad, even when payload encryption is disabled; an unset password yields an empty (but valid) key.
     */
    private byte[] keyBytes() {
        char[] pw = this.config.getPassword();
        if (pw == null || pw.length == 0) {
            return new byte[0];
        }
        return new String(pw).getBytes(StandardCharsets.UTF_8);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
