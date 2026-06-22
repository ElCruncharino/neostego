/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
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
 * <b>Tiled embedding.</b> The cover is partitioned into independent horizontal <em>bands</em> of at
 * most {@link #BAND_ELEMS} R/G/B samples each (a whole number of pixel rows). Every band is a
 * self-contained embedding problem &mdash; its own password-seeded permutation, its own HILL cost
 * map, and its own STC trellis &mdash; so the working-set memory (permutation, costs, trellis path)
 * is bounded by the band size rather than the whole image. This lets multi-megapixel covers be
 * embedded within a small heap (e.g. on Android) with no downscaling. The message bits are split
 * across the bands in proportion to each band's spare capacity, deterministically, so embed and
 * extract agree on the split from the image geometry and the stored data length alone.
 * <p>
 * Layout (all positions taken from per-band permutations of the R/G/B sample LSBs):
 * <ol>
 *   <li>band&nbsp;0 begins with a bootstrap header (reusing {@link LSBDataHeader} plus a 4-byte mode
 *       flag) written with LSB matching, so the receiver recovers the data length, flags, filename
 *       and CMD mode without knowing them in advance;</li>
 *   <li>the STC-coded body follows, spread across all bands using HILL costs.</li>
 * </ol>
 * The on-disk metadata reuses the existing {@code OPENSTEGO} header so the compression/encryption
 * flags flow back to the core unchanged. Existing plugins and their formats are untouched.
 */
public class AdaptiveImagePlugin extends DHImagePluginTemplate<AdaptiveConfig> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "Adaptive";

    /** STC constraint height used for embedding/extraction. */
    private static final int STC_HEIGHT = Stc.DEFAULT_HEIGHT;

    /** Number of header bytes used to store the embedding mode flag (0 = plain STC, 1 = CMD). */
    private static final int MODE_FIELD_BYTES = 4;

    /** Plain (single STC per band) mode marker. */
    private static final int MODE_PLAIN = 0;

    /** CMD (four sub-lattices per band) mode marker. */
    private static final int MODE_CMD = 1;

    /**
     * Maximum number of R/G/B sample LSBs processed per band. Caps the per-band working set
     * (permutation {@code int[]}, HILL cost {@code double[]}, STC {@code path long[]}) so peak memory
     * stays bounded regardless of image resolution. 2,000,000 elements is ~82&nbsp;MB of transient
     * arrays per band, comfortably within a constrained mobile heap.
     */
    private static final int BAND_ELEMS = 2_000_000;

    /**
     * Halo rows added above and below a band when computing its HILL cost, so that the cost at band
     * seams matches a whole-image computation. HILL's vertical reach is 9 rows (3&times;3 high-pass +
     * 3&times;3 L1 + 15&times;15 L2). Cost only affects detectability, never correctness.
     */
    private static final int COST_HALO = 9;

    /** Number of 2&times;2 sub-lattices used by the CMD embedding. */
    private static final int CMD_LATTICES = 4;

    /** Mixing constant (golden-ratio odd 64-bit) folded into the per-band permutation seed. */
    private static final long BAND_SEED_MIX = 0x9E3779B97F4A7C15L;

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
            int headerBits = (LSBDataHeader.getMaxHeaderSize() + MODE_FIELD_BYTES) * 8;
            int numOfPixels = (headerBits + msg.length * 8) / 3 + 16;
            image = ImageCodecRegistry.get().createRandomImage(numOfPixels);
        } else {
            image = ImageCodecRegistry.get().decode(cover, coverFileName);
        }
        int width = image.getWidth();
        int height = image.getHeight();

        // Bootstrap header (band 0): standard OpenStego header + a 4-byte embedding-mode flag.
        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int headerLen = headerBytes.length + MODE_FIELD_BYTES;
        int headerElems = headerLen * 8;

        int bodyBits = msg.length * 8;
        int[] caps = bandCaps(width, height, headerElems);
        long totalCap = 0;
        for (int c : caps) {
            totalCap += c;
        }
        if (bodyBits > 0 && totalCap < bodyBits) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.IMAGE_SIZE_INSUFFICIENT);
        }

        int mode = (this.config.isCmd() && bodyBits > 0) ? MODE_CMD : MODE_PLAIN;

        byte[] fullHeader = new byte[headerLen];
        System.arraycopy(headerBytes, 0, fullHeader, 0, headerBytes.length);
        putInt(fullHeader, headerBytes.length, mode);

        SecureRandom dir = new SecureRandom();

        // Band 0 permutation also carries the header in its first headerElems entries.
        int rpb = rowsPerBand(width);
        int numBands = caps.length;
        int[] perm0 = bandPermutation(bandCount(width, height, 0, rpb), this.config.getPassword(), 0);

        int bitPos = 0;
        for (byte b : fullHeader) {
            for (int k = 7; k >= 0; k--) {
                int bit = (b >> k) & 1;
                setMatchingLsb(image, width, perm0[bitPos++], bit, dir);
            }
        }

        if (bodyBits > 0) {
            int[] message = bytesToBits(msg);
            int[] bandBits = splitBits(bodyBits, caps);
            int off = 0;
            for (int b = 0; b < numBands; b++) {
                reportProgress((double) b / numBands);
                int bits = bandBits[b];
                if (bits == 0) {
                    continue;
                }
                int y0 = b * rpb;
                int y1 = Math.min(height, y0 + rpb);
                int start = y0 * width * 3;
                int count = (y1 - y0) * width * 3;
                int bodyStart = (b == 0) ? headerElems : 0;
                int[] perm = (b == 0) ? perm0 : bandPermutation(count, this.config.getPassword(), b);
                double[] cost = hillCostsBand(image, width, height, y0, y1);
                int[] seg = Arrays.copyOfRange(message, off, off + bits);

                if (mode == MODE_CMD) {
                    embedCmdBand(image, width, start, y0, y1, perm, bodyStart, seg, cost, this.config.getCmdMu());
                } else {
                    embedPlainBand(image, width, start, perm, bodyStart, seg, cost, dir);
                }
                off += bits;
            }
        }
        reportProgress(1.0);

        return ImageCodecRegistry.get().encode(image, stegoFileName);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] perm0 = bandPermutation(bandCount(width, height, 0, rowsPerBand(width)), this.config.getPassword(), 0);
        PermutedLsbInputStream in = new PermutedLsbInputStream(image, width, perm0);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        return header.getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        int width = image.getWidth();
        int height = image.getHeight();
        int rpb = rowsPerBand(width);

        int[] perm0 = bandPermutation(bandCount(width, height, 0, rpb), this.config.getPassword(), 0);
        PermutedLsbInputStream in = new PermutedLsbInputStream(image, width, perm0);
        LSBDataHeader header = new LSBDataHeader(in, this.config);
        byte[] modeBytes = new byte[MODE_FIELD_BYTES];
        if (in.read(modeBytes, 0, MODE_FIELD_BYTES) != MODE_FIELD_BYTES) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        int mode = getInt(modeBytes, 0);
        int dataLength = header.getDataLength();
        if (dataLength < 0 || (mode != MODE_PLAIN && mode != MODE_CMD)) {
            throw new OpenStegoException(null, NAMESPACE, AdaptiveErrors.ERR_IMAGE_DATA_READ);
        }
        int headerElems = in.elementsRead();
        byte[] data = new byte[dataLength];
        int bodyBits = dataLength * 8;
        if (bodyBits == 0) {
            return data;
        }

        int[] caps = bandCaps(width, height, headerElems);
        int[] bandBits = splitBits(bodyBits, caps);
        int numBands = caps.length;
        int[] allBits = new int[bodyBits];
        int off = 0;
        for (int b = 0; b < numBands; b++) {
            reportProgress((double) b / numBands);
            int bits = bandBits[b];
            if (bits == 0) {
                continue;
            }
            int y0 = b * rpb;
            int y1 = Math.min(height, y0 + rpb);
            int start = y0 * width * 3;
            int count = (y1 - y0) * width * 3;
            int bodyStart = (b == 0) ? headerElems : 0;
            int[] perm = (b == 0) ? perm0 : bandPermutation(count, this.config.getPassword(), b);
            int bodyCap = caps[b];

            if (mode == MODE_CMD) {
                extractCmdBand(image, width, start, perm, bodyStart, bits, allBits, off);
            } else {
                extractPlainBand(image, width, start, perm, bodyStart, bodyCap, bits, allBits, off);
            }
            off += bits;
        }
        reportProgress(1.0);
        bitsToBytes(allBits, data);
        return data;
    }

    /**
     * Returns the maximum number of message bytes that can be embedded in a cover of the given size
     * (at the highest-payload STC width of 1, with no embedded filename). Overrides the LSB-rate
     * default to account for the extra mode field that precedes the body; the header lives only in
     * band&nbsp;0, so the total body capacity is the same {@code n - headerElems} as before tiling.
     *
     * @param width  cover width in pixels
     * @param height cover height in pixels
     * @return maximum embeddable message length in bytes
     */
    @Override
    public int getMaxDataLength(int width, int height) {
        int n = width * height * 3;
        LSBDataHeader header = new LSBDataHeader(0, 1, null, this.config);
        int headerElems = (header.getHeaderData().length + MODE_FIELD_BYTES) * 8;
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

    // ---------------- band geometry ----------------

    /** Number of pixel rows per band, so a band holds at most {@link #BAND_ELEMS} R/G/B samples. */
    private static int rowsPerBand(int width) {
        return Math.max(1, BAND_ELEMS / (width * 3));
    }

    /** Number of R/G/B samples in band {@code b} given {@code rowsPerBand}. */
    private static int bandCount(int width, int height, int b, int rpb) {
        int y0 = b * rpb;
        int y1 = Math.min(height, y0 + rpb);
        return (y1 - y0) * width * 3;
    }

    /**
     * Per-band body capacities: each band's sample count, minus the header reservation in band&nbsp;0.
     * Identical on embed and extract (depends only on geometry and the fixed header size).
     */
    private static int[] bandCaps(int width, int height, int headerElems) {
        int rpb = rowsPerBand(width);
        int numBands = (height + rpb - 1) / rpb;
        int[] caps = new int[numBands];
        for (int b = 0; b < numBands; b++) {
            int count = bandCount(width, height, b, rpb);
            caps[b] = Math.max(0, (b == 0) ? count - headerElems : count);
        }
        return caps;
    }

    /**
     * Distributes {@code bodyBits} across bands in proportion to each band's capacity (floored), then
     * hands any rounding remainder to bands with spare capacity in order. Deterministic; sums to
     * {@code bodyBits} whenever {@code sum(caps) >= bodyBits}, with every {@code bits[b] <= caps[b]}.
     */
    private static int[] splitBits(int bodyBits, int[] caps) {
        int numBands = caps.length;
        int[] bits = new int[numBands];
        long total = 0;
        for (int c : caps) {
            total += c;
        }
        if (total == 0 || bodyBits == 0) {
            return bits;
        }
        int assigned = 0;
        for (int b = 0; b < numBands; b++) {
            bits[b] = (int) ((long) bodyBits * caps[b] / total);
            assigned += bits[b];
        }
        int rem = bodyBits - assigned;
        for (int b = 0; b < numBands && rem > 0; b++) {
            int add = Math.min(caps[b] - bits[b], rem);
            bits[b] += add;
            rem -= add;
        }
        return bits;
    }

    // ---------------- per-band embedding ----------------

    /** Plain mode: a single binary STC over the band's body elements. */
    private static void embedPlainBand(
            PixelImage image,
            int width,
            int start,
            int[] perm,
            int bodyStart,
            int[] seg,
            double[] cost,
            SecureRandom dir)
            throws OpenStegoException {
        int bits = seg.length;
        int bodyCap = perm.length - bodyStart;
        int w = bodyCap / bits;
        int used = bits * w;
        int[] x = new int[used];
        double[] rho = new double[used];
        for (int i = 0; i < used; i++) {
            int local = perm[bodyStart + i];
            x[i] = channelValue(image, width, start + local) & 1;
            rho[i] = cost[local];
        }
        int[] y = Stc.embed(x, rho, seg, w, STC_HEIGHT);
        for (int i = 0; i < used; i++) {
            if (y[i] != x[i]) {
                setMatchingLsb(image, width, start + perm[bodyStart + i], y[i], dir);
            }
        }
    }

    /** Plain mode extraction: re-derive width and {@link Stc#extract} the band's body. */
    private static void extractPlainBand(
            PixelImage image,
            int width,
            int start,
            int[] perm,
            int bodyStart,
            int bodyCap,
            int bits,
            int[] outBits,
            int outOff)
            throws OpenStegoException {
        int w = bodyCap / bits;
        int used = bits * w;
        int[] y = new int[used];
        for (int i = 0; i < used; i++) {
            y[i] = channelValue(image, width, start + perm[bodyStart + i]) & 1;
        }
        int[] seg = Stc.extract(y, bits, w, STC_HEIGHT);
        System.arraycopy(seg, 0, outBits, outOff, bits);
    }

    /**
     * CMD embedding within one band. The band's body elements are partitioned into four 2&times;2
     * sub-lattices by pixel parity and processed in order; the already-decided modification directions
     * of neighbouring (earlier) sub-lattices reduce the STC cost on the neighbour-aligned side, biasing
     * which pixels change and the &plusmn;1 direction so changes cluster (Li et al., TIFS 2015). The
     * direction map is band-local; neighbours outside the band's rows are simply ignored, which only
     * relaxes coherence at the (few) seam rows. Extraction never needs the directions.
     *
     * @param mu alignment cost-reduction factor (&ge;1; 1 disables the selection bias)
     */
    private static void embedCmdBand(
            PixelImage image,
            int width,
            int start,
            int y0,
            int y1,
            int[] perm,
            int bodyStart,
            int[] seg,
            double[] cost,
            double mu)
            throws OpenStegoException {
        byte[] dirMap = new byte[perm.length];
        int[][] lattice = subLatticesBand(perm, bodyStart, start, width);
        int[] segLen = segLengths(seg.length);

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
                int local = elems[j];
                int e = start + local;
                int value = channelValue(image, width, e);
                xg[j] = value & 1;
                double base = cost[local];
                int d;
                double c;
                if (value == 0) {
                    d = 1;
                    c = base;
                } else if (value == 255) {
                    d = -1;
                    c = base;
                } else {
                    int s = neighborDir(dirMap, e, start, width, y0, y1);
                    if (s > 0) {
                        d = 1;
                        c = base / mu;
                    } else if (s < 0) {
                        d = -1;
                        c = base / mu;
                    } else {
                        d = ((((long) e * 2654435761L) >>> 16) & 1L) == 0L ? 1 : -1;
                        c = base;
                    }
                }
                alignedDir[j] = d;
                rhog[j] = c;
            }
            int[] segG = Arrays.copyOfRange(seg, off, off + lg);
            int[] yg = Stc.embed(xg, rhog, segG, wg, STC_HEIGHT);
            for (int j = 0; j < used; j++) {
                if (yg[j] != xg[j]) {
                    int local = elems[j];
                    applyDelta(image, width, start + local, alignedDir[j]);
                    dirMap[local] = (byte) alignedDir[j];
                }
            }
            off += lg;
        }
    }

    /** CMD extraction within one band: re-partition identically and {@link Stc#extract} each sub-lattice. */
    private static void extractCmdBand(
            PixelImage image, int width, int start, int[] perm, int bodyStart, int bits, int[] outBits, int outOff)
            throws OpenStegoException {
        int[][] lattice = subLatticesBand(perm, bodyStart, start, width);
        int[] segLen = segLengths(bits);
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
                yg[j] = channelValue(image, width, start + elems[j]) & 1;
            }
            int[] seg = Stc.extract(yg, lg, wg, STC_HEIGHT);
            System.arraycopy(seg, 0, outBits, outOff + off, lg);
            off += lg;
        }
    }

    // ---------------- helpers ----------------

    /**
     * Builds a password-seeded permutation of {@code [0, count)} for band {@code bandIndex} (Fisher-Yates).
     * The band index is mixed into the seed so bands do not share an ordering. Entries are band-local
     * sample offsets; the caller adds the band's start element to reach a global index.
     */
    private static int[] bandPermutation(int count, char[] password, int bandIndex) throws OpenStegoException {
        int[] perm = new int[count];
        for (int i = 0; i < count; i++) {
            perm[i] = i;
        }
        long seed = StringUtil.passwordHash(password) ^ (bandIndex * BAND_SEED_MIX);
        Random rand = new Random(seed);
        for (int i = count - 1; i > 0; i--) {
            int j = rand.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        return perm;
    }

    /**
     * Computes HILL costs for one band's R/G/B samples, indexed by band-local element
     * {@code ((y-y0)*width + x)*3 + channel}. A {@link #COST_HALO}-row halo above and below the band is
     * included so the costs match a whole-image computation at the band's interior and seams.
     */
    private static double[] hillCostsBand(PixelImage image, int width, int height, int y0, int y1) {
        int bandRows = y1 - y0;
        double[] cost = new double[bandRows * width * 3];
        int hy0 = Math.max(0, y0 - COST_HALO);
        int hy1 = Math.min(height, y1 + COST_HALO);
        int haloRows = hy1 - hy0;
        int[][] channel = new int[haloRows][width];
        for (int ch = 0; ch < 3; ch++) {
            int shift = 16 - 8 * ch;
            for (int y = 0; y < haloRows; y++) {
                for (int x = 0; x < width; x++) {
                    channel[y][x] = (image.getRGB(x, hy0 + y) >> shift) & 0xFF;
                }
            }
            double[][] c = HillCost.cost(channel);
            for (int y = y0; y < y1; y++) {
                int cy = y - hy0;
                int ly = y - y0;
                for (int x = 0; x < width; x++) {
                    cost[(ly * width + x) * 3 + ch] = c[cy][x];
                }
            }
        }
        return cost;
    }

    /**
     * Partitions a band's body elements {@code perm[bodyStart..count)} into four lists by the 2&times;2
     * parity of their (global) pixel, preserving the permuted order. Stores band-local sample offsets.
     * Identical on embed and extract.
     */
    private static int[][] subLatticesBand(int[] perm, int bodyStart, int start, int width) {
        int count = perm.length;
        int remaining = count - bodyStart;
        int[] cnt = new int[CMD_LATTICES];
        for (int i = 0; i < remaining; i++) {
            int pixel = (start + perm[bodyStart + i]) / 3;
            cnt[((pixel / width) & 1) * 2 + ((pixel % width) & 1)]++;
        }
        int[][] lattice = new int[CMD_LATTICES][];
        for (int g = 0; g < CMD_LATTICES; g++) {
            lattice[g] = new int[cnt[g]];
        }
        int[] fill = new int[CMD_LATTICES];
        for (int i = 0; i < remaining; i++) {
            int local = perm[bodyStart + i];
            int pixel = (start + local) / 3;
            int g = ((pixel / width) & 1) * 2 + ((pixel % width) & 1);
            lattice[g][fill[g]++] = local;
        }
        return lattice;
    }

    /** Splits {@code l} message bits into four contiguous segment lengths (remainder to the last). */
    private static int[] segLengths(int l) {
        int q = l / CMD_LATTICES;
        return new int[] {q, q, q, l - 3 * q};
    }

    /**
     * Sum of decided modification directions over the 8 same-channel neighbours of element {@code e}
     * that lie within the current band's rows {@code [y0, y1)}. {@code dirMap} is band-local, indexed
     * by sample offset {@code globalElement - start}.
     */
    private static int neighborDir(byte[] dirMap, int e, int start, int width, int y0, int y1) {
        int pixel = e / 3;
        int ch = e % 3;
        int xc = pixel % width;
        int yc = pixel / width;
        int s = 0;
        for (int t = 0; t < 8; t++) {
            int nx = xc + NB_DX[t];
            int ny = yc + NB_DY[t];
            if (nx < 0 || nx >= width || ny < y0 || ny >= y1) {
                continue;
            }
            s += dirMap[(ny * width + nx) * 3 + ch - start];
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
