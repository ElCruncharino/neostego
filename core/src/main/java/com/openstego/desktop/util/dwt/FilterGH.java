/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.util.dwt;

/**
 * Object to store FilterGH data.
 * <p>
 * The library ever loads a single filter set (the biorthogonal filter formerly identified as filter id 1 in
 * {@code dwt/filters.xml}), so it is hardcoded here as {@link #FILTER_1} rather than parsed from XML.
 */
public class FilterGH {
    /**
     * Constant for filterGH type = Orthogonal
     */
    public static final int TYPE_ORTHOGONAL = 0;

    /**
     * Constant for filterGH type = Bi-orthogonal
     */
    public static final int TYPE_BIORTHOGONAL = 1;

    /**
     * The only wavelet filter this library uses: biorthogonal, formerly filter id 1 in {@code dwt/filters.xml}.
     */
    public static final FilterGH FILTER_1 = new FilterGH(
            TYPE_BIORTHOGONAL,
            new Filter(0, 2, true, new double[] {0.353553, -0.707107, 0.353553}),
            new Filter(-2, 2, false, new double[] {-0.176777, 0.353553, 1.060660, 0.353553, -0.176777}),
            new Filter(-1, 3, true, new double[] {0.176777, 0.353553, -1.060660, 0.353553, 0.176777}),
            new Filter(-1, 1, false, new double[] {0.353553, 0.707107, 0.353553}));

    /**
     * Type of the filterGH
     */
    private final int type;

    /**
     * Filter G
     */
    private final Filter g;

    /**
     * Filter H
     */
    private final Filter h;

    /**
     * Filter Gi
     */
    private final Filter gi;

    /**
     * Filter Hi
     */
    private final Filter hi;

    private FilterGH(int type, Filter g, Filter h, Filter gi, Filter hi) {
        this.type = type;
        this.g = g;
        this.h = h;
        this.gi = gi;
        this.hi = hi;
    }

    /**
     * Get method for type
     *
     * @return type
     */
    public int getType() {
        return this.type;
    }

    /**
     * Get method for filter g
     *
     * @return filter g
     */
    public Filter getG() {
        return this.g;
    }

    /**
     * Get method for filter h
     *
     * @return filter h
     */
    public Filter getH() {
        return this.h;
    }

    /**
     * Get method for filter gi
     *
     * @return filter gi
     */
    public Filter getGi() {
        return this.gi;
    }

    /**
     * Get method for filter hi
     *
     * @return filter hi
     */
    public Filter getHi() {
        return this.hi;
    }
}
