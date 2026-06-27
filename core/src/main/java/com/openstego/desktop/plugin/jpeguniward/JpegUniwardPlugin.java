/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
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
 *   <li>The AC coefficient positions (DC excluded) are partitioned into memory-bounded <em>bands</em>
 *       of contiguous DCT block-rows (at most {@link #BAND_ELEMS} carriers each), so cost, permutation
 *       and STC state never exceed one band &mdash; a full-resolution cover embeds in O(band), not
 *       O(image), heap. Within each band the carriers are password-permuted (seed mixed with the band
 *       index). Band&nbsp;0's permuted prefix carries a bootstrap field then the variable
 *       {@link LSBDataHeader}; the body is split deterministically across all bands in proportion to
 *       capacity, each carrier bit being the parity of a coefficient and each STC flip realised as the
 *       side-info &plusmn;1.</li>
 * </ol>
 * Extraction decodes the JPEG, rebuilds the identical band geometry and per-band permutations from the
 * (value-independent) block geometry, reads the header from band&nbsp;0, recomputes the same capacity
 * split, then {@link Stc#extract}s each band's slice &mdash; it needs neither the precover nor the
 * quality, and never computes a cost. The carrier set is the fixed AC-position grid, so a coefficient
 * that an edit drives to zero (or away from it) never desynchronises the receiver.
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

    /**
     * Fixed-geometry bootstrap field: two {@link #putInt} little-endian ints
     * {@code (headerByteLen, reserved)} = 8 bytes = 64 bits, STC-coded at {@link #BOOT_WIDTH} in
     * band&nbsp;0. Being a constant size and width, the receiver STC-extracts it with no side
     * information, then learns the byte length of the variable header that follows. The body's per-band
     * STC widths are derived from band geometry (not stored), so the second int is reserved.
     */
    private static final int BOOT_BYTES = 8;

    /**
     * Memory budget per band, in embeddable AC carriers. Bands are contiguous DCT block-row ranges
     * within a component holding at most this many carriers, so every O(n) working array (cost,
     * permutation, STC trellis) is bounded to one band regardless of image resolution.
     */
    private static final int BAND_ELEMS = 1_000_000;

    /**
     * Block-row halo added above and below a band when computing its UNIWARD cost, so each band's
     * coefficient costs match the whole-image computation exactly. The db8 wavelet's vertical reach is
     * under two 8-sample block-rows; two blocks of halo cover it with margin.
     */
    private static final int HALO_BLOCKS = 2;

    /** Golden-ratio odd constant mixed with the band index to decorrelate per-band permutations. */
    private static final long BAND_SEED_MIX = 0x9E3779B97F4A7C15L;

    /**
     * STC carrier width for the bootstrap field and the variable header. Deliberately generous: the
     * header is tiny, and a wide carrier lets STC realise its bits with very few, very low-cost
     * coefficient changes. Cost-blind header placement was the dominant detectability source, so the
     * header is now cost-driven exactly like the body.
     */
    private static final int BOOT_WIDTH = 16;

    /** STC carrier width for the variable {@link LSBDataHeader} bytes (see {@link #BOOT_WIDTH}). */
    private static final int HEADER_WIDTH = 16;

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
        // SI mode needs an uncompressed precover (JPEG in would discard the side information); plain
        // mode embeds directly into an already-compressed JPEG cover.
        return this.config.isPlainMode() ? Arrays.asList("jpg", "jpeg") : Arrays.asList("png", "bmp");
    }

    /**
     * The stego output is always a baseline JPEG (SI mode takes a PNG/BMP precover but still emits a
     * JPEG), so extraction only ever applies to JPEG input regardless of the configured mode.
     */
    @Override
    public boolean canExtractFrom(byte[] stegoData) {
        return com.openstego.desktop.util.ContainerType.detect(stegoData)
                == com.openstego.desktop.util.ContainerType.JPEG;
    }

    @Override
    public List<String> getWritableFileExtensions() {
        return Arrays.asList("jpg", "jpeg");
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        boolean plain = this.config.isPlainMode();
        JpegImage jpg;
        if (plain) {
            // Plain J-UNIWARD: embed directly into the supplied JPEG cover (no side information).
            if (cover == null) {
                throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_COVER_REQUIRED);
            }
            jpg = decode(cover);
        } else {
            PixelImage precover;
            if (cover == null) {
                // Carrier bits the embed path consumes: fixed bootstrap, the (over-estimated) variable
                // header at HEADER_WIDTH, and the body at width 1 (highest payload). ~1.4 AC carriers per
                // pixel at 4:2:0; one pixel per carrier bit is a safe over-estimate.
                long carrierBits = (long) BOOT_BYTES * 8 * BOOT_WIDTH
                        + (long) LSBDataHeader.getMaxHeaderSize() * 8 * HEADER_WIDTH
                        + (long) msg.length * 8;
                int numOfPixels = (int) carrierBits + 256;
                precover = ImageCodecRegistry.get().createRandomImage(numOfPixels);
            } else {
                precover = ImageCodecRegistry.get().decode(cover, coverFileName);
            }
            jpg = JpegCodec.fromPrecover(precover, this.config.getQuality());
        }
        int[][] bands = bandList(jpg);

        LSBDataHeader header = new LSBDataHeader(msg.length, 1, msgFileName, this.config);
        byte[] headerBytes = header.getHeaderData();
        int bootElems = BOOT_BYTES * 8 * BOOT_WIDTH;
        int headerElems = headerBytes.length * 8 * HEADER_WIDTH;
        int reserve = bootElems + headerElems;

        // Band 0 reserves its permuted prefix for the bootstrap + variable header; every band's
        // remaining carriers form the body capacity. The split is proportional and deterministic, so
        // extract reproduces it from geometry + the decoded header alone.
        int[] caps = bandCaps(jpg, bands, reserve);
        int bodyBits = msg.length * 8;
        long totalCap = 0;
        for (int cap : caps) {
            totalCap += cap;
        }
        if (caps[0] < 0 || (bodyBits > 0 && totalCap < bodyBits)) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.IMAGE_SIZE_INSUFFICIENT);
        }
        int[] bits = splitBits(bodyBits, caps);
        int[] msgBits = bytesToBits(msg);

        int off = 0;
        for (int b = 0; b < bands.length; b++) {
            reportProgress((double) b / bands.length);
            int c = bands[b][0];
            int r0 = bands[b][1];
            int r1 = bands[b][2];
            double[][] rounding = plain ? null : jpg.roundingStrip(c, r0, r1);
            double[][] cost = uniwardCostsBand(jpg, c, r0, r1, rounding);
            Elements el = enumerateBand(jpg, c, r0, r1, cost, rounding);
            int[] perm = bandPermutation(el.count, this.config.getPassword(), b);

            int bodyStart;
            if (b == 0) {
                // Bootstrap field: (variable-header byte length, reserved), STC-coded at a fixed width
                // so the receiver can read it with no side information.
                byte[] boot = new byte[BOOT_BYTES];
                putInt(boot, 0, headerBytes.length);
                putInt(boot, 4, 0);
                stcEmbedRegion(el, perm, 0, bytesToBits(boot), BOOT_WIDTH);
                // Variable header bytes, STC-coded (cost-aware) right after the bootstrap.
                stcEmbedRegion(el, perm, bootElems, bytesToBits(headerBytes), HEADER_WIDTH);
                bodyStart = reserve;
            } else {
                bodyStart = 0;
            }

            int segBits = bits[b];
            if (segBits > 0) {
                int w = caps[b] / segBits;
                int[] seg = Arrays.copyOfRange(msgBits, off, off + segBits);
                stcEmbedRegion(el, perm, bodyStart, seg, w);
                off += segBits;
            }
        }
        reportProgress(1.0);

        return JpegCodec.encode(jpg);
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        JpegImage jpg = decode(stegoData);
        int[][] bands = bandList(jpg);
        Elements el0 = enumerateBand(jpg, bands[0][0], bands[0][1], bands[0][2], null, null);
        int[] perm0 = bandPermutation(el0.count, this.config.getPassword(), 0);
        return readHeader(el0, perm0).getFileName();
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        JpegImage jpg = decode(stegoData);
        int[][] bands = bandList(jpg);

        // Band 0 carries the bootstrap + variable header in its permuted prefix.
        Elements el0 = enumerateBand(jpg, bands[0][0], bands[0][1], bands[0][2], null, null);
        int[] perm0 = bandPermutation(el0.count, this.config.getPassword(), 0);

        int[] bootBits = stcExtractRegion(el0, perm0, 0, BOOT_BYTES * 8, BOOT_WIDTH);
        byte[] boot = new byte[BOOT_BYTES];
        bitsToBytes(bootBits, boot);
        int headerByteLen = getInt(boot, 0);

        int bootElems = BOOT_BYTES * 8 * BOOT_WIDTH;
        if (headerByteLen < 0 || (long) bootElems + (long) headerByteLen * 8 * HEADER_WIDTH > el0.count) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int headerElems = headerByteLen * 8 * HEADER_WIDTH;
        int[] headerBitsArr = stcExtractRegion(el0, perm0, bootElems, headerByteLen * 8, HEADER_WIDTH);
        byte[] headerBytes = new byte[headerByteLen];
        bitsToBytes(headerBitsArr, headerBytes);

        LSBDataHeader header = new LSBDataHeader(new ByteArrayInputStream(headerBytes), this.config);
        int dataLength = header.getDataLength();
        if (dataLength < 0) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int reserve = bootElems + headerElems;
        int bodyBits = dataLength * 8;
        byte[] data = new byte[dataLength];
        if (bodyBits == 0) {
            return data;
        }

        // Recompute the identical capacity split, then extract each band's slice. Band 0's body
        // starts past its reserved header prefix; later bands use their whole permuted range.
        int[] caps = bandCaps(jpg, bands, reserve);
        long totalCap = 0;
        for (int cap : caps) {
            totalCap += cap;
        }
        if (caps[0] < 0 || totalCap < bodyBits) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int[] bits = splitBits(bodyBits, caps);

        int[] allBits = new int[bodyBits];
        int off = 0;
        for (int b = 0; b < bands.length; b++) {
            reportProgress((double) b / bands.length);
            int segBits = bits[b];
            if (segBits == 0) {
                continue;
            }
            Elements el = (b == 0) ? el0 : enumerateBand(jpg, bands[b][0], bands[b][1], bands[b][2], null, null);
            int[] perm = (b == 0) ? perm0 : bandPermutation(el.count, this.config.getPassword(), b);
            int w = caps[b] / segBits;
            int bodyStart = (b == 0) ? reserve : 0;
            if (w < 1 || (long) bodyStart + (long) segBits * w > el.count) {
                throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
            }
            int[] seg = stcExtractRegion(el, perm, bodyStart, segBits, w);
            System.arraycopy(seg, 0, allBits, off, segBits);
            off += segBits;
        }
        reportProgress(1.0);
        bitsToBytes(allBits, data);
        return data;
    }

    /** Reads the bootstrap field then the variable {@link LSBDataHeader} from band&nbsp;0. */
    private LSBDataHeader readHeader(Elements el, int[] perm) throws OpenStegoException {
        int[] bootBits = stcExtractRegion(el, perm, 0, BOOT_BYTES * 8, BOOT_WIDTH);
        byte[] boot = new byte[BOOT_BYTES];
        bitsToBytes(bootBits, boot);
        int headerByteLen = getInt(boot, 0);
        int bootElems = BOOT_BYTES * 8 * BOOT_WIDTH;
        if (headerByteLen < 0 || (long) bootElems + (long) headerByteLen * 8 * HEADER_WIDTH > el.count) {
            throw new OpenStegoException(null, NAMESPACE, JpegUniwardErrors.ERR_IMAGE_DATA_READ);
        }
        int[] headerBitsArr = stcExtractRegion(el, perm, bootElems, headerByteLen * 8, HEADER_WIDTH);
        byte[] headerBytes = new byte[headerByteLen];
        bitsToBytes(headerBitsArr, headerBytes);
        return new LSBDataHeader(new ByteArrayInputStream(headerBytes), this.config);
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
        int headerBytes = new LSBDataHeader(0, 1, null, getConfig()).getHeaderData().length;
        long used = (long) BOOT_BYTES * 8 * BOOT_WIDTH + (long) headerBytes * 8 * HEADER_WIDTH;
        long body = n - used;
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
        final short[][] block;
        final int[] k;
        final int[] dir;
        final double[] cost;

        Elements(int count, short[][] block, int[] k, int[] dir, double[] cost) {
            this.count = count;
            this.block = block;
            this.k = k;
            this.dir = dir;
            this.cost = cost;
        }
    }

    /**
     * Enumerates the embeddable AC coefficients of one band &mdash; component {@code c}, block-rows
     * {@code [r0, r1)} &mdash; in the same fixed geometric order the whole-image enumeration would use
     * (block raster, natural AC index 1..63), so embed and extract agree band for band. {@code block[e]}
     * references the live coefficient array, so parity reads/writes touch the JPEG directly. When
     * {@code cost != null} (embed) the SI change direction and band-local cost are filled in too;
     * {@code cost} and {@code rounding} are indexed by band-local block
     * {@code (br - r0) * blocksWide + bc}. On extract both are {@code null}.
     */
    private static Elements enumerateBand(JpegImage jpg, int c, int r0, int r1, double[][] cost, double[][] rounding) {
        int bw = jpg.getBlocksWide(c);
        int count = (r1 - r0) * bw * 63;
        short[][] block = new short[count][];
        int[] kArr = new int[count];
        int[] dir = (cost != null) ? new int[count] : null;
        double[] costArr = (cost != null) ? new double[count] : null;

        int idx = 0;
        for (int br = r0; br < r1; br++) {
            for (int bc = 0; bc < bw; bc++) {
                short[] blk = jpg.getBlock(c, br, bc);
                // Side information is present only in SI mode; plain J-UNIWARD (JPEG cover) has none.
                double[] err = (rounding != null) ? rounding[(br - r0) * bw + bc] : null;
                double[] cc = (cost != null) ? cost[(br - r0) * bw + bc] : null;
                for (int k = 1; k < 64; k++) {
                    block[idx] = blk;
                    kArr[idx] = k;
                    if (cost != null) {
                        // SI: flip toward the unrounded value (sign(e)). Plain: fixed +1; flip() clamps
                        // to the opposite direction at the AC_LIMIT boundary, and parity-based extraction
                        // is direction-agnostic, so a constant direction is correct.
                        if (err != null) {
                            double e = err[k];
                            dir[idx] = (e > 0) ? 1 : (e < 0 ? -1 : 1);
                        } else {
                            dir[idx] = 1;
                        }
                        costArr[idx] = cc[k];
                    }
                    idx++;
                }
            }
        }
        return new Elements(count, block, kArr, dir, costArr);
    }

    /**
     * UNIWARD costs for one band's blocks (component {@code c}, block-rows {@code [r0, r1)}),
     * side-info-scaled by {@code (1 - 2|e|)}. The cost is computed on the band's plane strip plus a
     * {@link #HALO_BLOCKS}-block halo above and below (clamped to the plane), so the band blocks'
     * costs are bit-identical to the whole-image computation; the halo blocks are discarded. The
     * band's rounding errors are passed in (computed once per band); returned indexed by band-local
     * block {@code (br - r0) * blocksWide + bc}.
     */
    private static double[][] uniwardCostsBand(JpegImage jpg, int c, int r0, int r1, double[][] rounding) {
        int bw = jpg.getBlocksWide(c);
        int bh = jpg.getBlocksHigh(c);
        int pw = jpg.getPlaneWidth(c);
        int ph = jpg.getPlaneHeight(c);

        int sr0 = Math.max(0, r0 - HALO_BLOCKS);
        int sr1 = Math.min(bh, r1 + HALO_BLOCKS);
        int planeTop = sr0 * 8;
        int planeBot = Math.min(ph, sr1 * 8);
        // SI mode reads the exact precover samples; plain mode (no precover) rebuilds the decompressed
        // cover plane from the stored coefficients (dequantize + inverse-DCT).
        double[][] strip = jpg.hasSideInfo()
                ? jpg.planeStrip(c, planeTop, planeBot)
                : jpg.decodedPlaneStrip(c, planeTop, planeBot);
        int stripH = strip.length;

        double[][] base = UniwardCost.compute(strip, stripH, pw, bw, sr1 - sr0, jpg.getQuantTable(c));

        double[][] out = new double[(r1 - r0) * bw][];
        for (int br = r0; br < r1; br++) {
            for (int bc = 0; bc < bw; bc++) {
                double[] rho = base[(br - sr0) * bw + bc];
                // Plain mode (rounding == null) uses the raw UNIWARD cost; SI mode scales by (1 - 2|e|),
                // making coefficients near a rounding boundary cheaper to change.
                if (rounding != null) {
                    double[] e = rounding[(br - r0) * bw + bc];
                    for (int k = 1; k < 64; k++) {
                        rho[k] *= (1.0 - 2.0 * Math.abs(e[k]));
                    }
                }
                out[(br - r0) * bw + bc] = rho;
            }
        }
        return out;
    }

    /**
     * Partitions every component's DCT block-rows into bands of at most {@link #BAND_ELEMS} carriers,
     * each {@code {component, r0, r1}} (block-rows {@code [r0, r1)}). The order &mdash; all of
     * component 0's bands, then component 1's, ... &mdash; is a pure function of the JPEG block
     * geometry, so embed and extract derive the identical band list. Band 0 holds the header.
     */
    private static int[][] bandList(JpegImage jpg) {
        int comps = jpg.getComponentCount();
        List<int[]> list = new ArrayList<>();
        for (int c = 0; c < comps; c++) {
            int bw = jpg.getBlocksWide(c);
            int bh = jpg.getBlocksHigh(c);
            int rowsPerBand = Math.max(1, BAND_ELEMS / (bw * 63));
            for (int r0 = 0; r0 < bh; r0 += rowsPerBand) {
                list.add(new int[] {c, r0, Math.min(bh, r0 + rowsPerBand)});
            }
        }
        return list.toArray(new int[0][]);
    }

    /**
     * Body carrier capacity of each band: a band's full carrier count, except band 0 which first
     * reserves {@code reserve} carriers for the bootstrap + variable header.
     */
    private static int[] bandCaps(JpegImage jpg, int[][] bands, int reserve) {
        int[] caps = new int[bands.length];
        for (int b = 0; b < bands.length; b++) {
            int bw = jpg.getBlocksWide(bands[b][0]);
            int count = (bands[b][2] - bands[b][1]) * bw * 63;
            caps[b] = (b == 0) ? count - reserve : count;
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

    private static int parity(Elements el, int e) {
        return el.block[e][el.k[e]] & 1;
    }

    /**
     * STC-embeds {@code bits} into the permuted carrier region {@code [start, start + bits.length*w)}
     * using the per-element SI-scaled UNIWARD costs, realising each STC flip as the side-info change.
     * The carrier parities (the LSBs) are the cover symbols; STC chooses which to flip to minimise cost.
     */
    private static void stcEmbedRegion(Elements el, int[] perm, int start, int[] bits, int w) {
        int elems = bits.length * w;
        int[] x = new int[elems];
        double[] rho = new double[elems];
        for (int i = 0; i < elems; i++) {
            int e = perm[start + i];
            x[i] = parity(el, e);
            rho[i] = el.cost[e];
        }
        int[] y = Stc.embed(x, rho, bits, w, STC_HEIGHT);
        for (int i = 0; i < elems; i++) {
            if (y[i] != x[i]) {
                flip(el, perm[start + i]);
            }
        }
    }

    /**
     * STC-extracts {@code numBits} message bits from the permuted carrier region
     * {@code [start, start + numBits*w)}. Cost-free: it reads only coefficient parities and the
     * (fixed or already-decoded) geometry, so it needs no side information.
     */
    private static int[] stcExtractRegion(Elements el, int[] perm, int start, int numBits, int w) {
        int elems = numBits * w;
        int[] y = new int[elems];
        for (int i = 0; i < elems; i++) {
            y[i] = parity(el, perm[start + i]);
        }
        return Stc.extract(y, numBits, w, STC_HEIGHT);
    }

    /** Flips a coefficient's parity by the side-info &plusmn;1, staying inside the AC value range. */
    private static void flip(Elements el, int e) {
        short[] b = el.block[e];
        int kk = el.k[e];
        int v = b[kk];
        int d = el.dir[e];
        int nv = v + d;
        if (nv > AC_LIMIT || nv < -AC_LIMIT) {
            nv = v - d; // the opposite parity-flipping step keeps us representable
        }
        b[kk] = (short) nv;
    }

    // ---------------- helpers ----------------

    private static JpegImage decode(byte[] stegoData) throws OpenStegoException {
        try {
            return JpegCodec.decode(stegoData);
        } catch (IOException ex) {
            throw new OpenStegoException(ex, NAMESPACE, JpegUniwardErrors.ERR_JPEG);
        }
    }

    /**
     * Builds a password-seeded permutation of one band's {@code [0, count)} carriers (Fisher-Yates).
     * The seed is mixed with the band index so each band draws an independent ordering, yet both
     * embed and extract reproduce it from the password and band index alone.
     */
    private static int[] bandPermutation(int count, char[] password, int bandIndex) throws OpenStegoException {
        int[] perm = new int[count];
        for (int i = 0; i < count; i++) {
            perm[i] = i;
        }
        Random rand = new Random(StringUtil.passwordHash(password) ^ (bandIndex * BAND_SEED_MIX));
        for (int i = count - 1; i > 0; i--) {
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
}
