/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.plugin.adaptive.Stc;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.StringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Side-Informed UNIWARD (SI-UNIWARD) JPEG steganography &mdash; the consensus most-secure practical
 * image steganography scheme, and NeoStego's flagship. The cover is an <em>uncompressed</em> precover
 * (PNG/BMP); the plugin owns the JPEG compression, so it holds the quantization rounding errors as
 * side information for free. Output is a baseline JPEG.
 * <p>
 * Pipeline:
 * <ol>
 *   <li>The precover is forward-DCT'd and quantized ({@link JpegCodec#fromPrecover}), keeping both the
 *       rounded coefficient {@code Q} and the rounding error {@code e = U/q - Q} in (&minus;0.5, 0.5].</li>
 *   <li>A real UNIWARD distortion cost ({@link UniwardCost}, Daubechies-8 directional wavelets) is
 *       computed per coefficient and scaled by the side information: {@code rho *= (1 - 2|e|)}, so a
 *       coefficient whose precover value sat near a rounding boundary is cheap to round the other way.
 *       The single allowed change direction is {@code sign(e)}.</li>
 *   <li>All AC coefficient positions (DC excluded) are flattened in a fixed geometric order and
 *       password-permuted. The first elements carry a bootstrap header (reusing {@link LSBDataHeader}
 *       plus a 4-byte STC width); the rest carry the STC-coded body, each carrier bit being the parity
 *       of a coefficient and each STC flip realised as the side-info &plusmn;1.</li>
 * </ol>
 * Extraction decodes the JPEG, rebuilds the identical permutation from the (value-independent) block
 * geometry, reads the header, then {@link Stc#extract}s the body &mdash; it needs neither the precover
 * nor the quality. The carrier set is the fixed AC-position grid, so a coefficient that an edit drives
 * to zero (or away from it) never desynchronises the receiver.
 * <p>
 * On-disk metadata reuses the existing {@code OPENSTEGO} header, so the compression/encryption flags
 * flow back to the core unchanged and existing plugins/files are untouched. Honest framing: this
 * raises the bar substantially against modern JPEG steganalysis; it is not "undetectable".
 */
public class JpegUniwardPlugin extends DHImagePluginTemplate<JpegUniwardConfig> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "JpegUniward";

    /** STC constraint height used for embedding/extraction. */
    private static final int STC_HEIGHT = Stc.DEFAULT_HEIGHT;

    /** Number of extra header bytes used to store the STC width. */
    private static final int WIDTH_FIELD_BYTES = 4;

    /** Baseline AC coefficients are limited to magnitude category 10, i.e. |value| &le; 1023. */
    private static final int AC_LIMIT = 1023;

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /**
     * Default constructor.
     */
    public JpegUniwardPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.JpegUniwardPluginLabels");
        JpegUniwardErrors.init();
    }

    @Override
    public String getName() {
        return "JpegUniward";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public List<String> getReadableFileExtensions() {
        // The cover is an uncompressed precover; JPEG in would discard the side information.
        return Arrays.asList("png", "bmp");
    }

    @Override
    public List<String> getWritableFileExtensions() {
        return Arrays.asList("jpg", "jpeg");
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        PixelImage precover;
        if (cover == null) {
            int headerBits = (LSBDataHeader.getMaxHeaderSize() + WIDTH_FIELD_BYTES) * 8;
            // ~1.4 AC carriers per pixel at 4:2:0; one pixel per carrier bit is a safe over-estimate.
            int numOfPixels = headerBits + msg.length * 8 + 256;
            precover = ImageCodecRegistry.get().createRandomImage(numOfPixels);
        } else {
            precover = ImageCodecRegistry.get().decode(cover, coverFileName);
        }

        JpegImage jpg = JpegCodec.fromPrecover(precover, this.config.getQuality());
        double[][][] cost = uniwardCosts(jpg);
        Elements el = enumerate(jpg, cost);
        int n = el.count;

        int[] perm = permutation(n, this.config.getPassword());

        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int headerLen = headerBytes.length + WIDTH_FIELD_BYTES;
        int headerElems = headerLen * 8;

        int bodyBits = msg.length * 8;
        int remaining = n - headerElems;
        if (remaining < 0 || (bodyBits > 0 && remaining < bodyBits)) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.IMAGE_SIZE_INSUFFICIENT);
        }
        int wField = (bodyBits == 0) ? 1 : remaining / bodyBits;

        byte[] fullHeader = new byte[headerLen];
        System.arraycopy(headerBytes, 0, fullHeader, 0, headerBytes.length);
        putInt(fullHeader, headerBytes.length, wField);

        // Write the header bits into the first headerElems permuted coefficients.
        int bitPos = 0;
        for (byte b : fullHeader) {
            for (int k = 7; k >= 0; k--) {
                setParity(el, perm[bitPos++], (b >> k) & 1);
            }
        }

        // STC-coded body.
        if (bodyBits > 0) {
            int w = wField;
            int bodyElems = bodyBits * w;
            int[] x = new int[bodyElems];
            double[] rho = new double[bodyElems];
            for (int i = 0; i < bodyElems; i++) {
                int e = perm[headerElems + i];
                x[i] = parity(el, e);
                rho[i] = el.cost[e];
            }
            int[] message = bytesToBits(msg);
            int[] y = Stc.embed(x, rho, message, w, STC_HEIGHT);
            for (int i = 0; i < bodyElems; i++) {
                if (y[i] != x[i]) {
                    flip(el, perm[headerElems + i]);
                }
            }
        }

        return JpegCodec.encode(jpg);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        JpegImage jpg = decode(stegoData);
        Elements el = enumerate(jpg, null);
        int[] perm = permutation(el.count, this.config.getPassword());
        PermutedCoeffInputStream in = new PermutedCoeffInputStream(el, perm);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        return header.getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        JpegImage jpg = decode(stegoData);
        Elements el = enumerate(jpg, null);
        int n = el.count;
        int[] perm = permutation(n, this.config.getPassword());

        PermutedCoeffInputStream in = new PermutedCoeffInputStream(el, perm);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        byte[] widthBytes = new byte[WIDTH_FIELD_BYTES];
        if (in.read(widthBytes, 0, WIDTH_FIELD_BYTES) != WIDTH_FIELD_BYTES) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int w = getInt(widthBytes, 0);
        int dataLength = header.getDataLength();
        if (dataLength < 0) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int bodyBits = dataLength * 8;
        int headerElems = in.elementsRead();
        byte[] data = new byte[dataLength];

        if (w < 1 || (long) headerElems + (long) bodyBits * w > n) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        if (bodyBits > 0) {
            int bodyElems = bodyBits * w;
            int[] y = new int[bodyElems];
            for (int i = 0; i < bodyElems; i++) {
                y[i] = parity(el, perm[headerElems + i]);
            }
            int[] bits = Stc.extract(y, bodyBits, w, STC_HEIGHT);
            bitsToBytes(bits, data);
        }
        return data;
    }

    /**
     * Maximum embeddable message length for a precover of the given pixel size, at the highest-payload
     * STC width of 1 (with no embedded filename). Computed from the 4:2:0 block geometry the embed
     * path uses; quality does not change the coefficient count.
     */
    @Override
    public int getMaxDataLength(int width, int height) {
        int mcuCols = ceilDiv(width, 16);
        int mcuRows = ceilDiv(height, 16);
        // Y is 2x2 blocks per MCU, Cb and Cr one each; 63 AC carriers per block.
        long blocks = (long) mcuCols * mcuRows * (4 + 1 + 1);
        long n = blocks * 63;
        int headerElems = (new LSBDataHeader(0, 1, null, getConfig()).getHeaderData().length
                + WIDTH_FIELD_BYTES) * 8;
        long body = n - headerElems;
        return (int) Math.max(0, body / 8);
    }

    @Override
    protected JpegUniwardConfig createConfig() {
        return new JpegUniwardConfig();
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }

    // ---------------- embeddable-element model ----------------

    /**
     * The flattened embeddable AC coefficients. {@code block[e]} is the live 64-entry coefficient
     * array and {@code k[e]} the natural-order AC index, so parity reads/writes touch the JPEG
     * directly. On the embed path {@code dir} (the side-info change direction) and {@code cost} (the
     * SI-scaled UNIWARD cost) are also populated; on extract they are left null.
     */
    private static final class Elements {
        final int count;
        final int[][] block;
        final int[] k;
        final int[] dir;
        final double[] cost;

        Elements(int count, int[][] block, int[] k, int[] dir, double[] cost) {
            this.count = count;
            this.block = block;
            this.k = k;
            this.dir = dir;
            this.cost = cost;
        }
    }

    /**
     * Flattens every AC coefficient position across all components in a fixed geometric order
     * (component, block raster, natural AC index 1..63). The order depends only on geometry, so embed
     * and extract enumerate identically. When {@code cost != null} (embed) the per-element side-info
     * direction and SI-scaled cost are filled in too.
     */
    private static Elements enumerate(JpegImage jpg, double[][][] cost) {
        int comps = jpg.getComponentCount();
        long total = 0;
        for (int c = 0; c < comps; c++) {
            total += (long) jpg.getBlocksWide(c) * jpg.getBlocksHigh(c) * 63;
        }
        int n = (int) total;
        int[][] block = new int[n][];
        int[] kArr = new int[n];
        int[] dir = (cost != null) ? new int[n] : null;
        double[] costArr = (cost != null) ? new double[n] : null;

        int idx = 0;
        for (int c = 0; c < comps; c++) {
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    int[] blk = jpg.getBlock(c, br, bc);
                    double[] err = (cost != null) ? jpg.getRounding(c, br, bc) : null;
                    double[] cc = (cost != null) ? cost[c][br * bw + bc] : null;
                    for (int k = 1; k < 64; k++) {
                        block[idx] = blk;
                        kArr[idx] = k;
                        if (cost != null) {
                            double e = err[k];
                            dir[idx] = (e > 0) ? 1 : (e < 0 ? -1 : 1);
                            costArr[idx] = cc[k];
                        }
                        idx++;
                    }
                }
            }
        }
        return new Elements(n, block, kArr, dir, costArr);
    }

    /** UNIWARD costs per component, side-info-scaled by {@code (1 - 2|e|)}. */
    private static double[][][] uniwardCosts(JpegImage jpg) {
        int comps = jpg.getComponentCount();
        double[][][] cost = new double[comps][][];
        for (int c = 0; c < comps; c++) {
            double[][] plane = jpg.getPlane(c);
            int ph = plane.length;
            int pw = plane[0].length;
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            double[][] base = UniwardCost.compute(plane, ph, pw, bw, bh, jpg.getQuantTable(c));
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    int bi = br * bw + bc;
                    double[] e = jpg.getRounding(c, br, bc);
                    double[] rho = base[bi];
                    for (int k = 1; k < 64; k++) {
                        rho[k] *= (1.0 - 2.0 * Math.abs(e[k]));
                    }
                }
            }
            cost[c] = base;
        }
        return cost;
    }

    private static int parity(Elements el, int e) {
        return el.block[e][el.k[e]] & 1;
    }

    private static void setParity(Elements el, int e, int bit) {
        if (parity(el, e) != bit) {
            flip(el, e);
        }
    }

    /** Flips a coefficient's parity by the side-info &plusmn;1, staying inside the AC value range. */
    private static void flip(Elements el, int e) {
        int[] b = el.block[e];
        int kk = el.k[e];
        int v = b[kk];
        int d = el.dir[e];
        int nv = v + d;
        if (nv > AC_LIMIT || nv < -AC_LIMIT) {
            nv = v - d; // the opposite parity-flipping step keeps us representable
        }
        b[kk] = nv;
    }

    // ---------------- helpers ----------------

    private static JpegImage decode(byte[] stegoData) throws OpenStegoException {
        try {
            return JpegCodec.decode(stegoData);
        } catch (IOException ex) {
            throw new OpenStegoException(ex, NAMESPACE, JpegUniwardErrors.ERR_JPEG);
        }
    }

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

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
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
     * An {@link InputStream} that yields bytes assembled MSB-first from the parities of the cover
     * coefficients in permutation order. Parses the bootstrap header; {@link #elementsRead()} reports
     * how many coefficients were consumed so the body can continue right after it.
     */
    private static final class PermutedCoeffInputStream extends InputStream {
        private final Elements el;
        private final int[] perm;
        private int pos;

        PermutedCoeffInputStream(Elements el, int[] perm) {
            this.el = el;
            this.perm = perm;
        }

        @Override
        public int read() {
            if (pos + 8 > perm.length) {
                return -1;
            }
            int b = 0;
            for (int k = 0; k < 8; k++) {
                b = (b << 1) | parity(el, perm[pos++]);
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
