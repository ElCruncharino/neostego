/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.dwt.Filter;
import com.openstego.desktop.util.dwt.FilterGH;
import com.openstego.desktop.util.dwt.Image;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory-frugal, single-level periodic DWT specialised for the DWT-SVD watermark.
 * <p>
 * The general-purpose {@link com.openstego.desktop.util.dwt.DWT}/{@link com.openstego.desktop.util.dwt.DWTUtil} path
 * materialises, for one forward+inverse round trip, the full-resolution luminance plane plus several full-size
 * {@code double[]} working buffers (an input copy, a half-width temporary and the four sub-bands), and the
 * watermark plugin additionally builds four full {@code int[][]} Y/U/V/alpha planes. On a multi-megapixel photo
 * that working set runs to roughly fifty bytes per source pixel - well over a gigabyte for a 24&nbsp;MP image - which
 * is what makes a large cover fail with {@link OutOfMemoryError} on a phone.
 * <p>
 * This class computes the <em>same</em> transform one image row at a time. The forward pass reads luminance rows on
 * demand from a {@link RowSource}, holds only a handful of horizontally-filtered rows (a sliding window plus the few
 * rows the periodic boundary wraps to), and retains just the sub-band coefficients themselves. The inverse pass
 * streams reconstructed luminance rows out to a {@link YRowSink}. Peak working memory drops from O(pixels) of
 * transient buffers to O(width) plus the resident sub-bands (the LL band carries the watermark and the periodic
 * inverse needs every band row available, so the bands stay resident; that alone is a ~6&times; reduction, and the
 * eliminated transients and chroma planes are the bulk of the rest).
 * <p>
 * Every arithmetic step here is a faithful, multiply-accumulate-order-preserving transcription of
 * {@code DWTUtil.filterPeriodical} / {@code filterInvPeriodical} and {@code DWTUtil.decomposition} /
 * {@code invDecomposition} for {@code filterID=1} (biorthogonal), {@code level=1}, {@code method=PERIODICAL}. The
 * coefficients are the very same {@link Filter} objects the legacy path loads. As a result the produced sub-bands
 * and the reconstructed pixels are <em>bit-identical</em> to the legacy path, so a watermark embedded (or verified)
 * through either path is interchangeable. {@code DwtSvdStreamingTest} asserts this coefficient-for-coefficient.
 */
final class DwtSvdTransform {

    /** Provides source luminance: the transform pulls one RGB row at a time and derives Y itself. */
    interface RowSource {
        int getWidth();

        int getHeight();

        /**
         * Read row {@code y} of the source as packed ARGB into {@code rgbRow} (length = width).
         *
         * @param y      Row index
         * @param rgbRow Destination buffer (0xAARRGGBB per pixel)
         */
        void readRow(int y, int[] rgbRow);
    }

    /** Receives reconstructed luminance rows (clamped 0..255 ints); the sink recombines chroma and writes pixels. */
    interface YRowSink {
        /**
         * Accept reconstructed luminance row {@code y}.
         *
         * @param y    Row index
         * @param yRow Luminance values for the row (length = width), each already clamped to [0, 255]
         */
        void writeYRow(int y, int[] yRow);
    }

    /** Forward analysis filters (low-pass H, high-pass G) - identical objects to those the legacy path uses. */
    private final Filter h;

    private final Filter g;

    /** Inverse synthesis filters. filterID=1 is biorthogonal, so {@code invDecomposition} uses Hi/Gi (not H/G). */
    private final Filter hi;

    private final Filter gi;

    private final int fullW;

    private final int fullH;

    private final int coarseW;

    private final int coarseH;

    DwtSvdTransform(int width, int height) throws OpenStegoException {
        FilterGH filterGH = FilterGH.FILTER_1;

        // Reject covers too small for a real level-1 decomposition. waveletTransform would, for such an image,
        // skip the transform entirely (returning the image as the "coarse" band with no detail) - a different
        // code path we deliberately do not stream. Any watermark-capable cover (it must hold dozens of 8x8 LL
        // blocks) is far larger than this floor, so this only ever rejects covers that could not carry a mark
        // anyway; the caller maps it to the standard "file too small" error.
        int min = Math.min(width, height);
        int maxLevel = ((int) (Math.log(min) / Math.log(2))) - 2;
        if (maxLevel < 1) {
            throw new OpenStegoException(null, DWTSVDPlugin.NAMESPACE, DWTSVDErrors.ERR_FILE_TOO_SMALL);
        }

        this.h = filterGH.getH();
        this.g = filterGH.getG();
        this.hi = filterGH.getHi();
        this.gi = filterGH.getGi();
        this.fullW = width;
        this.fullH = height;
        // Mirrors waveletTransform: one level halves each dimension as (n + 1) / 2.
        this.coarseW = (width + 1) / 2;
        this.coarseH = (height + 1) / 2;
    }

    int getCoarseWidth() {
        return this.coarseW;
    }

    int getCoarseHeight() {
        return this.coarseH;
    }

    // ------------------------------------------------------------------
    // Forward
    // ------------------------------------------------------------------

    /**
     * Forward single-level periodic DWT, streaming rows from {@code src}.
     *
     * @param src           Source of luminance rows
     * @param includeDetail When {@code false}, only the LL band is produced (sufficient for extraction/verification);
     *                      when {@code true}, all four sub-bands are produced (needed to reconstruct on embed)
     * @return array {LL, LH, HL, HH}; detail entries are {@code null} when {@code includeDetail} is {@code false}
     */
    Image[] forward(RowSource src, boolean includeDetail) {
        Image ll = new Image(this.coarseW, this.coarseH);
        Image lh = includeDetail ? new Image(this.coarseW, this.coarseH) : null;
        Image hl = includeDetail ? new Image(this.coarseW, this.coarseH) : null;
        Image hh = includeDetail ? new Image(this.coarseW, this.coarseH) : null;
        double[] llData = ll.getData();
        double[] lhData = includeDetail ? lh.getData() : null;
        double[] hlData = includeDetail ? hl.getData() : null;
        double[] hhData = includeDetail ? hh.getData() : null;

        RowFilterCache rows = new RowFilterCache(src);

        double[] hd = this.h.getData();
        double[] gd = this.g.getData();
        int hStart = this.h.getStart();
        int hEnd = this.h.getEnd();
        int gStart = this.g.getStart();
        int gEnd = this.g.getEnd();

        for (int i = 0; i < this.coarseH; i++) {
            int base = i * this.coarseW;

            // H-filter vertical pass: LL = Vh(Hh), HL = Vh(Gh). Both use filter H with the same iStart sequence,
            // so accumulate them together in one pass over the contributing rows (j inner, matching convoluteRows).
            int iStart = CommonUtil.mod((2 * i) - hStart, this.fullH);
            for (int j = hStart; j <= hEnd; j++) {
                double coef = hd[j - hStart];
                double[][] f = rows.get(iStart);
                double[] hRow = f[0];
                for (int x = 0; x < this.coarseW; x++) {
                    llData[base + x] += coef * hRow[x];
                }
                if (includeDetail) {
                    double[] gRow = f[1];
                    for (int x = 0; x < this.coarseW; x++) {
                        hlData[base + x] += coef * gRow[x];
                    }
                }
                iStart--;
                if (iStart < 0) {
                    iStart += this.fullH;
                }
            }

            // G-filter vertical pass: LH = Vg(Hh), HH = Vg(Gh). Detail bands only.
            if (includeDetail) {
                iStart = CommonUtil.mod((2 * i) - gStart, this.fullH);
                for (int j = gStart; j <= gEnd; j++) {
                    double coef = gd[j - gStart];
                    double[][] f = rows.get(iStart);
                    double[] hRow = f[0];
                    double[] gRow = f[1];
                    for (int x = 0; x < this.coarseW; x++) {
                        lhData[base + x] += coef * hRow[x];
                        hhData[base + x] += coef * gRow[x];
                    }
                    iStart--;
                    if (iStart < 0) {
                        iStart += this.fullH;
                    }
                }
            }

            rows.retainFor(i + 1);
        }

        return new Image[] {ll, lh, hl, hh};
    }

    // ------------------------------------------------------------------
    // Inverse
    // ------------------------------------------------------------------

    /**
     * Inverse single-level periodic DWT, streaming reconstructed luminance rows to {@code sink}. Mirrors
     * {@code DWTUtil.invDecomposition} for a biorthogonal filter: a vertical synthesis (Hi over LL/HL, Gi over LH/HH)
     * followed by a horizontal synthesis, with the two halves summed - reproducing the exact accumulation order.
     *
     * @param bands array {LL, LH, HL, HH} as produced by {@link #forward} (LL may have been modified to embed)
     * @param sink  Destination for reconstructed luminance rows
     */
    void inverse(Image[] bands, YRowSink sink) {
        double[] llData = bands[0].getData();
        double[] lhData = bands[1].getData();
        double[] hlData = bands[2].getData();
        double[] hhData = bands[3].getData();

        double[] hid = this.hi.getData();
        double[] gid = this.gi.getData();
        int hiStart = this.hi.getStart();
        int hiEnd = this.hi.getEnd();
        int giStart = this.gi.getStart();
        int giEnd = this.gi.getEnd();

        // temp1 = vertical synthesis of the low-pass column (Hi*LL + Gi*LH); temp2 = high-pass column (Hi*HL + Gi*HH).
        double[] temp1 = new double[this.coarseW];
        double[] temp2 = new double[this.coarseW];
        int[] yRow = new int[this.fullW];

        for (int i = 0; i < this.fullH; i++) {
            // ---- vertical inverse (per column, inLen = coarseH) ----
            int hiFs = CommonUtil.ceilingHalf(hiStart + i);
            int hiFe = CommonUtil.floorHalf(hiEnd + i);
            int giFs = CommonUtil.ceilingHalf(giStart + i);
            int giFe = CommonUtil.floorHalf(giEnd + i);

            for (int x = 0; x < this.coarseW; x++) {
                // Accumulate Gi terms onto the Hi sum (same cell, j order) exactly as the two convoluteRows calls do.
                double acc1 = 0.0;
                int iStart = CommonUtil.mod(hiFs, this.coarseH);
                for (int j = hiFs; j <= hiFe; j++) {
                    acc1 += hid[(2 * j) - i - hiStart] * llData[iStart * this.coarseW + x];
                    iStart++;
                    if (iStart >= this.coarseH) {
                        iStart -= this.coarseH;
                    }
                }
                iStart = CommonUtil.mod(giFs, this.coarseH);
                for (int j = giFs; j <= giFe; j++) {
                    acc1 += gid[(2 * j) - i - giStart] * lhData[iStart * this.coarseW + x];
                    iStart++;
                    if (iStart >= this.coarseH) {
                        iStart -= this.coarseH;
                    }
                }
                temp1[x] = acc1;

                double acc2 = 0.0;
                iStart = CommonUtil.mod(hiFs, this.coarseH);
                for (int j = hiFs; j <= hiFe; j++) {
                    acc2 += hid[(2 * j) - i - hiStart] * hlData[iStart * this.coarseW + x];
                    iStart++;
                    if (iStart >= this.coarseH) {
                        iStart -= this.coarseH;
                    }
                }
                iStart = CommonUtil.mod(giFs, this.coarseH);
                for (int j = giFs; j <= giFe; j++) {
                    acc2 += gid[(2 * j) - i - giStart] * hhData[iStart * this.coarseW + x];
                    iStart++;
                    if (iStart >= this.coarseH) {
                        iStart -= this.coarseH;
                    }
                }
                temp2[x] = acc2;
            }

            // ---- horizontal inverse (per output column, inLen = coarseW) ----
            for (int col = 0; col < this.fullW; col++) {
                double acc = 0.0;
                int fs = CommonUtil.ceilingHalf(hiStart + col);
                int fe = CommonUtil.floorHalf(hiEnd + col);
                int iStart = CommonUtil.mod(fs, this.coarseW);
                for (int j = fs; j <= fe; j++) {
                    acc += hid[(2 * j) - col - hiStart] * temp1[iStart];
                    iStart++;
                    if (iStart >= this.coarseW) {
                        iStart -= this.coarseW;
                    }
                }
                fs = CommonUtil.ceilingHalf(giStart + col);
                fe = CommonUtil.floorHalf(giEnd + col);
                iStart = CommonUtil.mod(fs, this.coarseW);
                for (int j = fs; j <= fe; j++) {
                    acc += gid[(2 * j) - col - giStart] * temp2[iStart];
                    iStart++;
                    if (iStart >= this.coarseW) {
                        iStart -= this.coarseW;
                    }
                }
                // Identical rounding/clamping to DWT.inverseDWT: (int)(v + 0.5) truncates toward zero, then clamp.
                int p = (int) (acc + 0.5);
                yRow[col] = (p > 255) ? 255 : Math.max(p, 0);
            }

            sink.writeYRow(i, yRow);
        }
    }

    // ------------------------------------------------------------------
    // Horizontal-filtered row cache (the only O(width) working state of the forward pass)
    // ------------------------------------------------------------------

    /**
     * Lazily computes and caches the horizontally H- and G-filtered versions of source luminance rows. Rows are read
     * from the source on demand (in whatever order the periodic vertical convolution needs them, including the few
     * boundary wraps) and evicted once no upcoming output row needs them, bounding the cache to a handful of rows.
     */
    private final class RowFilterCache {
        private final RowSource src;

        private final int[] rgbRow;

        private final double[] yRow;

        private final Map<Integer, double[][]> cache = new HashMap<>();

        RowFilterCache(RowSource src) {
            this.src = src;
            this.rgbRow = new int[DwtSvdTransform.this.fullW];
            this.yRow = new double[DwtSvdTransform.this.fullW];
        }

        /** Returns {hFilteredRow, gFilteredRow} (each length coarseW) for source row {@code r}. */
        double[][] get(int r) {
            double[][] cached = this.cache.get(r);
            if (cached != null) {
                return cached;
            }

            this.src.readRow(r, this.rgbRow);
            for (int x = 0; x < DwtSvdTransform.this.fullW; x++) {
                int rgb = this.rgbRow[x];
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                // Exactly YuvImageUtil's BT.601 luma with the same truncation to int, fed to the transform as a double
                // (the legacy path stores this int via DWTUtil.setPixel).
                this.yRow[x] = (int) ((0.299 * red) + (0.587 * green) + (0.114 * blue));
            }

            double[] hRow = new double[DwtSvdTransform.this.coarseW];
            double[] gRow = new double[DwtSvdTransform.this.coarseW];
            horizontal(DwtSvdTransform.this.h, this.yRow, hRow);
            horizontal(DwtSvdTransform.this.g, this.yRow, gRow);

            double[][] result = new double[][] {hRow, gRow};
            this.cache.put(r, result);
            return result;
        }

        /** Drop cached rows that output row {@code nextOutput} (and beyond, this step) will not read. */
        void retainFor(int nextOutput) {
            if (nextOutput >= DwtSvdTransform.this.coarseH) {
                this.cache.clear();
                return;
            }
            // Output row k reads source rows (2k - 2) .. (2k + 2) mod fullH (union of the H and G filter supports).
            int lo = (2 * nextOutput) - 2;
            int hiIdx = (2 * nextOutput) + 2;
            this.cache.keySet().removeIf(r -> {
                for (int raw = lo; raw <= hiIdx; raw++) {
                    if (CommonUtil.mod(raw, DwtSvdTransform.this.fullH) == r) {
                        return false;
                    }
                }
                return true;
            });
        }

        /**
         * Horizontal periodic convolution of one full-width row into a coarseW-length output (matches
         * filterPeriodical).
         */
        private void horizontal(Filter filter, double[] in, double[] out) {
            int fStart = filter.getStart();
            int fEnd = filter.getEnd();
            double[] fd = filter.getData();
            int inLen = DwtSvdTransform.this.fullW;
            for (int i = 0; i < DwtSvdTransform.this.coarseW; i++) {
                int iStart = CommonUtil.mod((2 * i) - fStart, inLen);
                double acc = 0.0;
                for (int j = fStart; j <= fEnd; j++) {
                    acc += fd[j - fStart] * in[iStart];
                    iStart--;
                    if (iStart < 0) {
                        iStart += inLen;
                    }
                }
                out[i] = acc;
            }
        }
    }

    // ------------------------------------------------------------------
    // In-memory PixelImage adapters (desktop, and Android fallback)
    // ------------------------------------------------------------------

    /** A {@link RowSource} reading directly from a resident {@link PixelImage}. */
    static RowSource pixelSource(PixelImage image) {
        return new RowSource() {
            @Override
            public int getWidth() {
                return image.getWidth();
            }

            @Override
            public int getHeight() {
                return image.getHeight();
            }

            @Override
            public void readRow(int y, int[] rgbRow) {
                int w = image.getWidth();
                for (int x = 0; x < w; x++) {
                    rgbRow[x] = image.getRGB(x, y);
                }
            }
        };
    }

    /**
     * A {@link YRowSink} that writes reconstructed luminance back into a resident {@link PixelImage}, recombining the
     * original chroma. Reproduces {@code YuvImageUtil.getYuvFromImage} + {@code applyYuvToImage} exactly: it derives
     * U/V/alpha from the pixel's <em>original</em> RGB (read just before it is overwritten) and the new luminance.
     */
    static YRowSink pixelSink(PixelImage image) {
        return (y, yRow) -> {
            int w = image.getWidth();
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                int u = (int) ((-0.147 * red) - (0.289 * green) + (0.436 * blue));
                int v = (int) ((0.615 * red) - (0.515 * green) - (0.100 * blue));
                int yv = yRow[x];

                int r2 = clamp(yv + 1.140 * v);
                int g2 = clamp(yv - 0.395 * u - 0.581 * v);
                int b2 = clamp(yv + 2.032 * u);
                image.setRGB(x, y, (alpha << 24) | (r2 << 16) | (g2 << 8) | b2);
            }
        };
    }

    private static int clamp(double p) {
        return (p > 255) ? 255 : (p < 0) ? 0 : (int) p;
    }
}
