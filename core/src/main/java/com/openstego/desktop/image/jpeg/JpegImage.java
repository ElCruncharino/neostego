/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image.jpeg;

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
    /** [component][blockIndex][64] quantized coefficients, natural order. */
    private final int[][][] coeff;
    /** [component][blockIndex][64] rounding errors, or {@code null} after a plain decode. */
    private final double[][][] rounding;

    JpegImage(int width, int height, Component[] components, int[][] quantTables,
            int maxH, int maxV, int mcuCols, int mcuRows,
            int[][][] coeff, double[][][] rounding) {
        this.width = width;
        this.height = height;
        this.components = components;
        this.quantTables = quantTables;
        this.maxH = maxH;
        this.maxV = maxV;
        this.mcuCols = mcuCols;
        this.mcuRows = mcuRows;
        this.coeff = coeff;
        this.rounding = rounding;
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
    public int[] getBlock(int comp, int blockRow, int blockCol) {
        return this.coeff[comp][blockRow * this.components[comp].blocksWide + blockCol];
    }

    /**
     * Returns the rounding-error array for a block, or {@code null} if no side information is
     * available (plain decode). Indexed like {@link #getBlock}.
     */
    public double[] getRounding(int comp, int blockRow, int blockCol) {
        if (this.rounding == null) {
            return null;
        }
        return this.rounding[comp][blockRow * this.components[comp].blocksWide + blockCol];
    }

    /** @return the quantization table (natural order, 64 entries) used by a component. */
    public int[] getQuantTable(int comp) {
        return this.quantTables[this.components[comp].quantTableId];
    }

    /** @return whether per-coefficient rounding-error side information is present. */
    public boolean hasSideInfo() {
        return this.rounding != null;
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
            for (int[] block : this.coeff[c]) {
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

    int[][] coeff(int comp) {
        return this.coeff[comp];
    }
}
