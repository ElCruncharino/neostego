/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

/**
 * Callback for reporting the progress of a long-running embedding, extraction or watermarking
 * operation. Implementations receive a completion fraction in {@code [0.0, 1.0]} as the work
 * proceeds, suitable for driving a determinate progress bar and a time estimate.
 * <p>
 * Progress is reported at the natural work boundaries of each algorithm (e.g. after each image band
 * or block-grid pass), so the number of callbacks is small and the fraction is monotonically
 * non-decreasing within a single operation. Plugins that do not report progress simply never invoke
 * the listener, in which case the caller should fall back to an indeterminate indicator.
 * <p>
 * The callback runs on the worker thread performing the operation; front-ends are responsible for
 * marshalling updates onto their UI thread.
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * Reports the current completion fraction of the operation.
     *
     * @param fraction completion ratio in {@code [0.0, 1.0]}
     */
    void onProgress(double fraction);
}
