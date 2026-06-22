/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStegoException;

/**
 * Error codes for the SI-UNIWARD JPEG plugin.
 */
public class JpegUniwardErrors {

    /** Error Code - Cover capacity insufficient for the data */
    public static final int IMAGE_SIZE_INSUFFICIENT = 1;

    /** Error Code - Error while reading the embedded data */
    public static final int ERR_IMAGE_DATA_READ = 2;

    /** Error Code - Malformed or unsupported JPEG stream */
    public static final int ERR_JPEG = 3;

    /** Error Code - Plain mode invoked without a JPEG cover */
    public static final int ERR_COVER_REQUIRED = 4;

    /**
     * Registers the error-code to message-key mappings.
     */
    public static void init() {
        OpenStegoException.addErrorCode(
                JpegUniwardPlugin.NAMESPACE, IMAGE_SIZE_INSUFFICIENT, "err.image.insufficientSize");
        OpenStegoException.addErrorCode(JpegUniwardPlugin.NAMESPACE, ERR_IMAGE_DATA_READ, "err.image.read");
        OpenStegoException.addErrorCode(JpegUniwardPlugin.NAMESPACE, ERR_JPEG, "err.jpeg.invalid");
        OpenStegoException.addErrorCode(JpegUniwardPlugin.NAMESPACE, ERR_COVER_REQUIRED, "err.cover.required");
    }
}
