/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

/**
 * Optional platform-supplied fast path for {@link UniwardCost#compute}. Unlike {@code ImageCodecRegistry}
 * (where every platform must supply a codec), this is a pure optimization: {@link #get} returns
 * {@code null} when no accelerator is registered, and callers fall back to the portable Java
 * implementation. There is no requirement that the accelerator's output be bit-identical to the Java
 * path -- STC extraction is a syndrome computation over the stego bits, independent of the exact cost
 * values used at embed time, so a differently-rounded (but algorithmically equivalent) cost only ever
 * nudges which near-tied coefficient gets picked, never breaks correctness.
 */
public final class UniwardCostAccelerator {

    /** Computes the same result as {@link UniwardCost#compute}, using a platform-specific fast path. */
    public interface Impl {
        double[][] compute(double[][] plane, int planeH, int planeW, int blocksWide, int blocksHigh, int[] quant);
    }

    private static volatile Impl impl;

    private UniwardCostAccelerator() {}

    public static Impl get() {
        return impl;
    }

    public static void set(Impl accelerator) {
        impl = accelerator;
    }
}
