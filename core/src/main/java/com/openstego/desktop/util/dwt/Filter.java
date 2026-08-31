/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.util.dwt;

/**
 * Object to store Filter data
 */
public class Filter {
    /**
     * Constant for filter method = cutoff
     */
    public static final int METHOD_CUTOFF = 0;

    /**
     * Constant for filter method = inv_cutoff
     */
    public static final int METHOD_INVCUTOFF = 1;

    /**
     * Constant for filter method = periodical
     */
    public static final int METHOD_PERIODICAL = 2;

    /**
     * Constant for filter method = inv_periodical
     */
    public static final int METHOD_INVPERIODICAL = 3;

    /**
     * Constant for filter method = mirror,inv_mirror
     */
    public static final int METHOD_MIRROR = 4;

    /**
     * Constant for filter method = inv_mirror
     */
    public static final int METHOD_INVMIRROR = 5;

    /**
     * Start value of the filter
     */
    private final int start;

    /**
     * End value of the filter
     */
    private final int end;

    /**
     * Flag to indicate whether this is hi-pass filter or not
     */
    private final boolean hiPass;

    /**
     * List of associated data
     */
    private final double[] data;

    /**
     * Constructor
     *
     * @param start  Start value of the filter
     * @param end    End value of the filter
     * @param hiPass Whether this is a hi-pass filter
     * @param data   Filter coefficients
     */
    Filter(int start, int end, boolean hiPass, double[] data) {
        this.start = start;
        this.end = end;
        this.hiPass = hiPass;
        this.data = data;
    }

    /**
     * Get method for start
     *
     * @return start
     */
    public int getStart() {
        return this.start;
    }

    /**
     * Get method for end
     *
     * @return end
     */
    public int getEnd() {
        return this.end;
    }

    /**
     * Get method for hiPass
     *
     * @return hiPass
     */
    public boolean isHiPass() {
        return this.hiPass;
    }

    /**
     * Get method for data
     *
     * @return data
     */
    public double[] getData() {
        return this.data;
    }
}
