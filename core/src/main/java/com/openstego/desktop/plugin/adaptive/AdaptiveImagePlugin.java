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
import java.util.Arrays;
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

    /**
     * Sentinel written into the width field to mark a v2 (CMD) stego stream. {@code -1} is never a
     * valid v1 width (which is always {@code >= 1}), so the decoder can branch unambiguously while
     * keeping the header byte-size identical to v1.
     */
    private static final int V2_SENTINEL = -1;

    /** Number of 2&times;2 sub-lattices used by the CMD (v2) embedding. */
    private static final int CMD_LATTICES = 4;

    /** 8-neighbour offsets (same channel) used to measure the local modification trend for CMD. */
    private static final int[] NB_DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] NB_DY = {-1, -1, -1, 0, 0, 1, 1, 1};

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
        if (bodyBits > 0 && remaining < bodyBits) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.IMAGE_SIZE_INSUFFICIENT);
        }

        boolean useCmd = this.config.isCmd() && bodyBits > 0;
        int wField;
        if (useCmd) {
            wField = V2_SENTINEL;
        } else if (bodyBits == 0) {
            wField = 1;
        } else {
            wField = remaining / bodyBits;
        }

        byte[] fullHeader = new byte[headerLen];
        System.arraycopy(headerBytes, 0, fullHeader, 0, headerBytes.length);
        putInt(fullHeader, headerBytes.length, wField);

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
        if (bodyBits > 0) {
            int[] message = bytesToBits(msg);
            double[] costAll = hillCosts(image, width);
            if (useCmd) {
                embedCmd(image, width, perm, headerElems, n, message, costAll, this.config.getCmdMu());
            } else {
                int w = wField;
                int bodyElems = bodyBits * w;
                int[] x = new int[bodyElems];
                double[] rho = new double[bodyElems];
                for (int i = 0; i < bodyElems; i++) {
                    int e = perm[headerElems + i];
                    x[i] = channelValue(image, width, e) & 1;
                    rho[i] = costAll[e];
                }
                int[] y = Stc.embed(x, rho, message, w, STC_HEIGHT);
                for (int i = 0; i < bodyElems; i++) {
                    if (y[i] != x[i]) {
                        setMatchingLsb(image, width, perm[headerElems + i], y[i], dir);
                    }
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
        if (dataLength < 0) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        int bodyBits = dataLength * 8;
        int headerElems = in.elementsRead();
        byte[] data = new byte[dataLength];

        if (w == V2_SENTINEL) {
            // v2 (CMD): the body is split across 2x2 sub-lattices, each its own binary STC.
            if (bodyBits > 0) {
                extractCmd(image, width, perm, headerElems, n, bodyBits, data);
            }
            return data;
        }

        // v1: a single binary STC over all body elements.
        if (w < 1 || (long) headerElems + (long) bodyBits * w > n) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        if (bodyBits > 0) {
            int bodyElems = bodyBits * w;
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
     * Returns the maximum number of message bytes that can be embedded in a cover of the given size
     * (at the highest-payload STC width of 1, with no embedded filename). Overrides the LSB-rate
     * default to account for the extra STC-width field that precedes the body.
     *
     * @param width  cover width in pixels
     * @param height cover height in pixels
     * @return maximum embeddable message length in bytes
     */
    @Override
    public int getMaxDataLength(int width, int height) {
        int n = width * height * 3;
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

    /**
     * CMD (v2) embedding. The body elements are partitioned into four 2&times;2 sub-lattices by pixel
     * parity and processed in order. As each sub-lattice is embedded with its own binary STC, the
     * already-decided modification directions of the neighbouring (earlier) sub-lattices are used to
     * <em>reduce the STC cost on the neighbour-aligned side</em>, biasing both which pixels change and
     * the &plusmn;1 direction so that changes cluster into coherent groups (Li et al., TIFS 2015).
     * <p>
     * Extraction never needs the directions: it reads only LSBs, and a flipped LSB is the same bit
     * regardless of whether it was reached by +1 or &minus;1. Each sub-lattice is an independent,
     * already-tested binary STC problem, so this reuses {@link Stc} verbatim with no new coding math.
     *
     * @param mu alignment cost-reduction factor (&ge;1; 1 disables the selection bias)
     */
    private static void embedCmd(PixelImage image, int width, int[] perm, int headerElems, int n,
                                 int[] message, double[] costAll, double mu) throws OpenStegoException {
        int height = image.getHeight();
        int[] dirMap = new int[n];
        int[][] lattice = subLattices(perm, headerElems, n, width);
        int[] segLen = segLengths(message.length);

        int off = 0;
        for (int g = 0; g < CMD_LATTICES; g++) {
            int lg = segLen[g];
            if (lg == 0) {
                continue;
            }
            int[] elems = lattice[g];
            int ng = elems.length;
            if (ng < lg) {
                throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.IMAGE_SIZE_INSUFFICIENT);
            }
            int wg = ng / lg;
            int used = lg * wg;
            int[] xg = new int[used];
            double[] rhog = new double[used];
            int[] alignedDir = new int[used];
            for (int j = 0; j < used; j++) {
                int e = elems[j];
                int value = channelValue(image, width, e);
                xg[j] = value & 1;
                double base = costAll[e];
                int d;
                double cost;
                if (value == 0) {
                    d = 1;
                    cost = base;
                } else if (value == 255) {
                    d = -1;
                    cost = base;
                } else {
                    int s = neighborDir(dirMap, e, width, height);
                    if (s > 0) {
                        d = 1;
                        cost = base / mu;
                    } else if (s < 0) {
                        d = -1;
                        cost = base / mu;
                    } else {
                        // No local trend: deterministic tie-break, no selection bias.
                        d = ((((long) e * 2654435761L) >>> 16) & 1L) == 0L ? 1 : -1;
                        cost = base;
                    }
                }
                alignedDir[j] = d;
                rhog[j] = cost;
            }
            int[] seg = Arrays.copyOfRange(message, off, off + lg);
            int[] yg = Stc.embed(xg, rhog, seg, wg, STC_HEIGHT);
            for (int j = 0; j < used; j++) {
                if (yg[j] != xg[j]) {
                    int e = elems[j];
                    applyDelta(image, width, e, alignedDir[j]);
                    dirMap[e] = alignedDir[j];
                }
            }
            off += lg;
        }
    }

    /** CMD (v2) extraction: re-partition identically and {@link Stc#extract} each sub-lattice. */
    private static void extractCmd(PixelImage image, int width, int[] perm, int headerElems, int n,
                                   int bodyBits, byte[] data) throws OpenStegoException {
        int[][] lattice = subLattices(perm, headerElems, n, width);
        int[] segLen = segLengths(bodyBits);
        int[] bits = new int[bodyBits];
        int off = 0;
        for (int g = 0; g < CMD_LATTICES; g++) {
            int lg = segLen[g];
            if (lg == 0) {
                continue;
            }
            int[] elems = lattice[g];
            int ng = elems.length;
            if (ng < lg) {
                throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
            }
            int wg = ng / lg;
            int used = lg * wg;
            int[] yg = new int[used];
            for (int j = 0; j < used; j++) {
                yg[j] = channelValue(image, width, elems[j]) & 1;
            }
            int[] seg = Stc.extract(yg, lg, wg, STC_HEIGHT);
            System.arraycopy(seg, 0, bits, off, lg);
            off += lg;
        }
        bitsToBytes(bits, data);
    }

    /**
     * Partitions the body elements {@code perm[headerElems..n)} into four lists by the 2&times;2 parity
     * of their pixel, preserving the permuted order. Identical on embed and extract.
     */
    private static int[][] subLattices(int[] perm, int headerElems, int n, int width) {
        int remaining = n - headerElems;
        int[] cnt = new int[CMD_LATTICES];
        for (int i = 0; i < remaining; i++) {
            int pixel = perm[headerElems + i] / 3;
            cnt[((pixel / width) & 1) * 2 + ((pixel % width) & 1)]++;
        }
        int[][] lattice = new int[CMD_LATTICES][];
        for (int g = 0; g < CMD_LATTICES; g++) {
            lattice[g] = new int[cnt[g]];
        }
        int[] fill = new int[CMD_LATTICES];
        for (int i = 0; i < remaining; i++) {
            int e = perm[headerElems + i];
            int pixel = e / 3;
            int g = ((pixel / width) & 1) * 2 + ((pixel % width) & 1);
            lattice[g][fill[g]++] = e;
        }
        return lattice;
    }

    /** Splits {@code l} message bits into four contiguous segment lengths (remainder to the last). */
    private static int[] segLengths(int l) {
        int q = l / CMD_LATTICES;
        return new int[] {q, q, q, l - 3 * q};
    }

    /** Sum of decided modification directions over the 8 same-channel neighbours of element {@code e}. */
    private static int neighborDir(int[] dirMap, int e, int width, int height) {
        int pixel = e / 3;
        int ch = e % 3;
        int xc = pixel % width;
        int yc = pixel / width;
        int s = 0;
        for (int t = 0; t < 8; t++) {
            int nx = xc + NB_DX[t];
            int ny = yc + NB_DY[t];
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                continue;
            }
            s += dirMap[(ny * width + nx) * 3 + ch];
        }
        return s;
    }

    /** Applies a fixed &plusmn;1 change in direction {@code d} to element {@code e} (clamped at borders). */
    private static void applyDelta(PixelImage image, int width, int e, int d) {
        int pixel = e / 3;
        int ch = e % 3;
        int x = pixel % width;
        int y = pixel / width;
        int shift = 16 - 8 * ch;
        int rgb = image.getRGB(x, y);
        int value = (rgb >> shift) & 0xFF;
        if (value == 0) {
            value = 1;
        } else if (value == 255) {
            value = 254;
        } else {
            value += d;
        }
        rgb = (rgb & ~(0xFF << shift)) | ((value & 0xFF) << shift);
        image.setRGB(x, y, rgb);
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
