/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.StringUtil;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Content-adaptive image steganography plugin combining the HILL embedding-cost function with
 * Syndrome-Trellis Codes (STC). Changes are concentrated in textured regions (low HILL cost) and
 * coded with STC so that the fewest, best-hidden pixels carry the payload, and each change is
 * realised as a random &plusmn;1 (LSB matching). This resists both classical statistical
 * steganalysis and raises the bar against CNN steganalysis, far beyond uniform LSB replacement.
 * <p>
 * Layout (all positions taken from a password-seeded permutation of the R/G/B sample LSBs):
 * <ol>
 *   <li>a bootstrap header (reusing {@link LSBDataHeader} plus a 4-byte STC width) written with LSB
 *       matching into the first elements, so the receiver can recover the data length, flags,
 *       filename and width without knowing them in advance;</li>
 *   <li>the STC-coded body in the following elements, using HILL costs.</li>
 * </ol>
 * The on-disk metadata reuses the existing {@code OPENSTEGO} header so the compression/encryption
 * flags flow back to the core unchanged. Existing plugins and their formats are untouched.
 */
public class AdaptiveImagePlugin extends DHImagePluginTemplate<AdaptiveConfig> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "Adaptive";

    /** STC constraint height used for embedding/extraction. */
    private static final int STC_HEIGHT = Stc.DEFAULT_HEIGHT;

    /** Number of extra header bytes used to store the STC width. */
    private static final int WIDTH_FIELD_BYTES = 4;

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /**
     * Default constructor.
     */
    public AdaptiveImagePlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.AdaptivePluginLabels");
        AdaptiveErrors.init();
    }

    @Override
    public String getName() {
        return "Adaptive";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        PixelImage image;
        if (cover == null) {
            int headerBits = (LSBDataHeader.getMaxHeaderSize() + WIDTH_FIELD_BYTES) * 8;
            int numOfPixels = (headerBits + msg.length * 8) / 3 + 16;
            image = ImageCodecRegistry.get().createRandomImage(numOfPixels);
        } else {
            image = ImageCodecRegistry.get().decode(cover, coverFileName);
        }
        int width = image.getWidth();
        int n = width * image.getHeight() * 3;

        int[] perm = permutation(n, this.config.getPassword());

        // Bootstrap header: standard OpenStego header + STC width
        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int headerLen = headerBytes.length + WIDTH_FIELD_BYTES;
        int headerElems = headerLen * 8;

        int bodyBits = msg.length * 8;
        int remaining = n - headerElems;
        int w;
        int bodyElems;
        if (bodyBits == 0) {
            w = 1;
            bodyElems = 0;
        } else {
            if (remaining < bodyBits) {
                throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.IMAGE_SIZE_INSUFFICIENT);
            }
            w = remaining / bodyBits;
            bodyElems = bodyBits * w;
        }

        byte[] fullHeader = new byte[headerLen];
        System.arraycopy(headerBytes, 0, fullHeader, 0, headerBytes.length);
        putInt(fullHeader, headerBytes.length, w);

        SecureRandom dir = new SecureRandom();

        // Write the header bits (LSB matching) into the first headerElems permuted elements
        int bitPos = 0;
        for (byte b : fullHeader) {
            for (int k = 7; k >= 0; k--) {
                int bit = (b >> k) & 1;
                setMatchingLsb(image, width, perm[bitPos++], bit, dir);
            }
        }

        // STC-coded body
        if (bodyElems > 0) {
            double[] costAll = hillCosts(image, width);
            int[] x = new int[bodyElems];
            double[] rho = new double[bodyElems];
            for (int i = 0; i < bodyElems; i++) {
                int e = perm[headerElems + i];
                x[i] = channelValue(image, width, e) & 1;
                rho[i] = costAll[e];
            }
            int[] message = bytesToBits(msg);
            int[] y = Stc.embed(x, rho, message, w, STC_HEIGHT);
            for (int i = 0; i < bodyElems; i++) {
                if (y[i] != x[i]) {
                    setMatchingLsb(image, width, perm[headerElems + i], y[i], dir);
                }
            }
        }

        return ImageCodecRegistry.get().encode(image, stegoFileName);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int width = image.getWidth();
        int n = width * image.getHeight() * 3;
        int[] perm = permutation(n, this.config.getPassword());
        PermutedLsbInputStream in = new PermutedLsbInputStream(image, width, perm);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        return header.getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int width = image.getWidth();
        int n = width * image.getHeight() * 3;
        int[] perm = permutation(n, this.config.getPassword());

        PermutedLsbInputStream in = new PermutedLsbInputStream(image, width, perm);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        byte[] widthBytes = new byte[WIDTH_FIELD_BYTES];
        if (in.read(widthBytes, 0, WIDTH_FIELD_BYTES) != WIDTH_FIELD_BYTES) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        int w = getInt(widthBytes, 0);
        int dataLength = header.getDataLength();
        int bodyBits = dataLength * 8;
        int headerElems = in.elementsRead();
        if (dataLength < 0 || w < 1 || (long) headerElems + (long) bodyBits * w > n) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        int bodyElems = bodyBits * w;

        byte[] data = new byte[dataLength];
        if (bodyBits > 0) {
            int[] y = new int[bodyElems];
            for (int i = 0; i < bodyElems; i++) {
                y[i] = channelValue(image, width, perm[headerElems + i]) & 1;
            }
            int[] bits = Stc.extract(y, bodyBits, w, STC_HEIGHT);
            bitsToBytes(bits, data);
        }
        return data;
    }

    /**
     * Returns the maximum number of message bytes that can be embedded in the given image (at the
     * highest-payload width of 1, with no embedded filename). Useful for a capacity indicator.
     *
     * @param image cover image
     * @return maximum embeddable message length in bytes
     */
    public int getMaxDataLength(PixelImage image) {
        int n = image.getWidth() * image.getHeight() * 3;
        LSBDataHeader header = new LSBDataHeader(0, 1, null, this.config);
        int headerElems = (header.getHeaderData().length + WIDTH_FIELD_BYTES) * 8;
        int bodyElems = n - headerElems;
        return Math.max(0, bodyElems / 8);
    }

    @Override
    protected AdaptiveConfig createConfig() {
        return new AdaptiveConfig();
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }

    // ---------------- helpers ----------------

    /** Builds a password-seeded permutation of {@code [0, n)} (Fisher-Yates). */
    private static int[] permutation(int n, char[] password) throws OpenStegoException {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        Random rand = new Random(StringUtil.passwordHash(password));
        for (int i = n - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        return perm;
    }

    /** Computes HILL costs for every R/G/B sample, indexed as {@code (y*width + x)*3 + channel}. */
    private static double[] hillCosts(PixelImage image, int width) {
        int height = image.getHeight();
        double[] costAll = new double[width * height * 3];
        int[][] channel = new int[height][width];
        for (int ch = 0; ch < 3; ch++) {
            int shift = 16 - 8 * ch;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    channel[y][x] = (image.getRGB(x, y) >> shift) & 0xFF;
                }
            }
            double[][] cost = HillCost.cost(channel);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    costAll[(y * width + x) * 3 + ch] = cost[y][x];
                }
            }
        }
        return costAll;
    }

    /** Reads a single R/G/B sample value (0..255) for element index {@code e}. */
    private static int channelValue(PixelImage image, int width, int e) {
        int pixel = e / 3;
        int ch = e % 3;
        int x = pixel % width;
        int y = pixel / width;
        int shift = 16 - 8 * ch;
        return (image.getRGB(x, y) >> shift) & 0xFF;
    }

    /** Sets the LSB of element {@code e} to {@code bit} via a random &plusmn;1 change (LSB matching). */
    private static void setMatchingLsb(PixelImage image, int width, int e, int bit, Random dir) {
        int pixel = e / 3;
        int ch = e % 3;
        int x = pixel % width;
        int y = pixel / width;
        int shift = 16 - 8 * ch;
        int rgb = image.getRGB(x, y);
        int value = (rgb >> shift) & 0xFF;
        if ((value & 1) == bit) {
            return;
        }
        if (value == 255) {
            value--;
        } else if (value == 0) {
            value++;
        } else {
            value += dir.nextBoolean() ? 1 : -1;
        }
        rgb = (rgb & ~(0xFF << shift)) | ((value & 0xFF) << shift);
        image.setRGB(x, y, rgb);
    }

    private static int[] bytesToBits(byte[] data) {
        int[] bits = new int[data.length * 8];
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                bits[i * 8 + k] = (b >> (7 - k)) & 1;
            }
        }
        return bits;
    }

    private static void bitsToBytes(int[] bits, byte[] out) {
        for (int i = 0; i < out.length; i++) {
            int b = 0;
            for (int k = 0; k < 8; k++) {
                b = (b << 1) | (bits[i * 8 + k] & 1);
            }
            out[i] = (byte) b;
        }
    }

    private static void putInt(byte[] buf, int off, int value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static int getInt(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    /**
     * An {@link InputStream} that yields bytes assembled MSB-first from the LSBs of the cover samples
     * in permutation order. Used to parse the bootstrap header; {@link #elementsRead()} reports how
     * many samples were consumed so the body can continue right after it.
     */
    private static final class PermutedLsbInputStream extends InputStream {
        private final PixelImage image;
        private final int width;
        private final int[] perm;
        private int pos;

        PermutedLsbInputStream(PixelImage image, int width, int[] perm) {
            this.image = image;
            this.width = width;
            this.perm = perm;
        }

        @Override
        public int read() {
            if (pos + 8 > perm.length) {
                return -1;
            }
            int b = 0;
            for (int k = 0; k < 8; k++) {
                b = (b << 1) | (channelValue(image, width, perm[pos++]) & 1);
            }
            return b & 0xFF;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            int count = 0;
            for (int i = 0; i < len; i++) {
                int v = read();
                if (v == -1) {
                    return count == 0 ? -1 : count;
                }
                buf[off + i] = (byte) v;
                count++;
            }
            return count;
        }

        int elementsRead() {
            return pos;
        }
    }
}
