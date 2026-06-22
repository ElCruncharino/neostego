/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.jpeg;

import com.openstego.desktop.image.PixelImage;

/**
 * In-memory representation of a baseline JPEG as <em>quantized</em> DCT coefficients &mdash; the
 * level at which steganographic embedding happens. Coefficients are kept in natural (row-major) 8x8
 * order, never dequantized; {@link JpegCodec} entropy-codes them back verbatim, so any edit applied
 * here survives a re-encode exactly.
 * <p>
 * For a cover produced from an uncompressed precover (see
 * {@link JpegCodec#fromPrecover(com.openstego.desktop.image.PixelImage, int)}) the per-coefficient
 * rounding errors are retained as side information: {@code e = U/q - Q} with {@code e} in
 * (&minus;0.5, 0.5], where {@code U} is the unrounded transform value. Side-informed schemes use
 * {@code |e|} to scale costs and {@code sign(e)} to pick the change direction. After a plain decode
 * the side information is {@code null}.
 */
public final class JpegImage {

    /** One color component's identity, sampling factors and quant-table selector. */
    static final class Component {
        int id;
        int hSamp;
        int vSamp;
        int quantTableId;
        /** blocks per component row = mcuCols * hSamp. */
        int blocksWide;
        /** component rows of blocks = mcuRows * vSamp. */
        int blocksHigh;
    }

    private final int width;
    private final int height;
    private final Component[] components;
    /** Up to 4 quantization tables, natural order; entries may be {@code null}. */
    private final int[][] quantTables;

    private final int maxH;
    private final int maxV;
    private final int mcuCols;
    private final int mcuRows;
    /**
     * [component][blockIndex][64] quantized coefficients, natural order. Stored as {@code short}: AC
     * coefficients are clamped to magnitude category 10 (|value| &le; 1023) and the DC term fits a
     * {@code short} with wide margin, so this halves the resident coefficient footprint versus
     * {@code int} &mdash; the dominant whole-image array for a large cover.
     */
    private final short[][][] coeff;
    /**
     * The uncompressed precover, retained on the {@link JpegCodec#fromPrecover} path so the spatial
     * sample planes and per-coefficient rounding errors can be recomputed on demand, one band at a
     * time, instead of being held full-resolution. {@code null} after a plain decode (no side info).
     */
    private final PixelImage precover;
    /** Whether chroma is 4:2:0 sub-sampled; only meaningful when {@link #precover} is non-null. */
    private final boolean subsample;

    JpegImage(
            int width,
            int height,
            Component[] components,
            int[][] quantTables,
            int maxH,
            int maxV,
            int mcuCols,
            int mcuRows,
            short[][][] coeff,
            PixelImage precover,
            boolean subsample) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.quantTables = quantTables;
        this.maxH = maxH;
        this.maxV = maxV;
        this.mcuCols = mcuCols;
        this.mcuRows = mcuRows;
        this.coeff = coeff;
        this.precover = precover;
        this.subsample = subsample;
    }

    /** @return image width in pixels. */
    public int getWidth() {
        return this.width;
    }

    /** @return image height in pixels. */
    public int getHeight() {
        return this.height;
    }

    /** @return number of color components (1 = grayscale, 3 = YCbCr). */
    public int getComponentCount() {
        return this.components.length;
    }

    /** @return blocks per row for a component. */
    public int getBlocksWide(int comp) {
        return this.components[comp].blocksWide;
    }

    /** @return rows of blocks for a component. */
    public int getBlocksHigh(int comp) {
        return this.components[comp].blocksHigh;
    }

    /**
     * Returns the live 64-entry coefficient array for one block (natural order). Edits to the
     * returned array are persisted on the next {@link JpegCodec#encode}.
     *
     * @param comp     component index
     * @param blockRow block row within the component
     * @param blockCol block column within the component
     * @return live coefficient array (index 0 = DC)
     */
    public short[] getBlock(int comp, int blockRow, int blockCol) {
        return this.coeff[comp][blockRow * this.components[comp].blocksWide + blockCol];
    }

    /**
     * Returns the rounding-error array for a single block, recomputed from the retained precover, or
     * {@code null} if no side information is available (plain decode). Indexed like {@link #getBlock}.
     * <p>
     * This recomputes the block-row's transform on each call; side-informed embedding should instead
     * fetch a whole band's errors once via {@link #roundingStrip}.
     */
    public double[] getRounding(int comp, int blockRow, int blockCol) {
        if (this.precover == null) {
            return null;
        }
        return roundingStrip(comp, blockRow, blockRow + 1)[blockCol];
    }

    /** @return the quantization table (natural order, 64 entries) used by a component. */
    public int[] getQuantTable(int comp) {
        return this.quantTables[this.components[comp].quantTableId];
    }

    /** @return whether per-coefficient rounding-error side information is present. */
    public boolean hasSideInfo() {
        return this.precover != null;
    }

    /** @return component {@code comp}'s spatial-sample plane width (luma full-size, chroma sub-sampled). */
    public int getPlaneWidth(int comp) {
        return JpegCodec.planeWidth(comp, this.width, this.subsample);
    }

    /** @return component {@code comp}'s spatial-sample plane height. */
    public int getPlaneHeight(int comp) {
        return JpegCodec.planeHeight(comp, this.height, this.subsample);
    }

    /**
     * Recomputes a strip of component {@code comp}'s spatial sample plane &mdash; plane rows
     * {@code [row0, row1)}, clamped to the plane &mdash; from the retained precover, as
     * {@code [rows][planeWidth]} doubles. Bounded to the requested rows, so a band's cost can be
     * computed without ever materialising the whole plane. {@code null} after a plain decode.
     *
     * @param comp component index
     * @param row0 first plane row (inclusive)
     * @param row1 last plane row (exclusive)
     * @return the clamped sample strip, or {@code null} if no side information is available
     */
    public double[][] planeStrip(int comp, int row0, int row1) {
        if (this.precover == null) {
            return null;
        }
        int pw = getPlaneWidth(comp);
        int ph = getPlaneHeight(comp);
        int a = Math.max(0, row0);
        int b = Math.min(ph, row1);
        int n = Math.max(0, b - a);
        double[][] out = new double[n][pw];
        for (int i = 0; i < n; i++) {
            JpegCodec.planeRow(this.precover, comp, this.subsample, a + i, out[i]);
        }
        return out;
    }

    /**
     * Reconstructs a strip of component {@code comp}'s decompressed spatial sample plane &mdash; plane
     * rows {@code [row0, row1)}, clamped to the plane &mdash; directly from the stored quantized
     * coefficients (dequantize then inverse-DCT), as {@code [rows][planeWidth]} doubles. This is the
     * plain (no-side-information) counterpart of {@link #planeStrip}: it works on an already-compressed
     * JPEG cover where there is no precover to read samples from, returning the very image a decoder
     * would show. Whole DCT blocks covering the requested rows are transformed; samples past the plane
     * edge are dropped (the cost code clamp-replicates them anyway).
     *
     * @param comp component index
     * @param row0 first plane row (inclusive)
     * @param row1 last plane row (exclusive)
     * @return the clamped, decompressed sample strip
     */
    public double[][] decodedPlaneStrip(int comp, int row0, int row1) {
        int pw = getPlaneWidth(comp);
        int ph = getPlaneHeight(comp);
        int a = Math.max(0, row0);
        int b = Math.min(ph, row1);
        int n = Math.max(0, b - a);
        double[][] out = new double[n][pw];
        int bw = this.components[comp].blocksWide;
        int[] quant = getQuantTable(comp);
        double[] block = new double[64];
        double[] samples = new double[64];
        int br0 = a / 8;
        int br1 = (b + 7) / 8;
        for (int br = br0; br < br1; br++) {
            for (int bc = 0; bc < bw; bc++) {
                short[] q = getBlock(comp, br, bc);
                for (int k = 0; k < 64; k++) {
                    block[k] = q[k] * (double) quant[k];
                }
                Dct8x8.inverse(block, samples);
                int baseRow = br * 8;
                int baseCol = bc * 8;
                for (int r = 0; r < 8; r++) {
                    int py = baseRow + r;
                    if (py < a || py >= b) {
                        continue;
                    }
                    double[] orow = out[py - a];
                    for (int cc = 0; cc < 8; cc++) {
                        int px = baseCol + cc;
                        if (px >= pw) {
                            break;
                        }
                        orow[px] = samples[r * 8 + cc];
                    }
                }
            }
        }
        return out;
    }

    /**
     * Recomputes the per-coefficient rounding errors for component {@code comp}'s block-rows
     * {@code [blockRow0, blockRow1)} from the retained precover, as {@code [(rows)*blocksWide][64]}
     * indexed by band-local block {@code (br - blockRow0) * blocksWide + bc}. Bit-identical to the
     * full-image build; bounded to the band so side-informed embedding stays O(band). {@code null}
     * after a plain decode.
     */
    public double[][] roundingStrip(int comp, int blockRow0, int blockRow1) {
        if (this.precover == null) {
            return null;
        }
        int bw = this.components[comp].blocksWide;
        int n = Math.max(0, (blockRow1 - blockRow0) * bw);
        double[][] err = new double[n][64];
        JpegCodec.transformBlockRows(
                this.precover, comp, this.subsample, blockRow0, blockRow1, bw, getQuantTable(comp), null, err);
        return err;
    }

    /**
     * Returns the full spatial sample plane for a component (precover only), as {@code [row][col]}
     * doubles in the component's own resolution. Convenience for analysis tooling; the memory-bounded
     * embed path uses {@link #planeStrip} instead. {@code null} after a plain decode.
     *
     * @param comp component index
     * @return spatial plane, or {@code null} if no side information is available
     */
    public double[][] getPlane(int comp) {
        if (this.precover == null) {
            return null;
        }
        return planeStrip(comp, 0, getPlaneHeight(comp));
    }

    /**
     * Counts non-zero AC coefficients across all components &mdash; the embeddable positions for a
     * DCT-domain scheme (the DC term and zero ACs are excluded).
     *
     * @return number of non-zero AC coefficients
     */
    public long nonZeroAcCount() {
        long n = 0;
        for (int c = 0; c < this.components.length; c++) {
            for (short[] block : this.coeff[c]) {
                for (int k = 1; k < 64; k++) {
                    if (block[k] != 0) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // Package-private geometry accessors for the codec.

    Component[] components() {
        return this.components;
    }

    int[][] quantTables() {
        return this.quantTables;
    }

    int maxH() {
        return this.maxH;
    }

    int maxV() {
        return this.maxV;
    }

    int mcuCols() {
        return this.mcuCols;
    }

    int mcuRows() {
        return this.mcuRows;
    }

    short[][] coeff(int comp) {
        return this.coeff[comp];
    }
}
