/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.OpenStegoException;

/**
 * Error codes for the F5 JPEG plugin.
 */
public class F5Errors {

    /** Error Code - Cover capacity insufficient for the data */
    public static final int IMAGE_SIZE_INSUFFICIENT = 1;

    /** Error Code - Error while reading the embedded data */
    public static final int ERR_IMAGE_DATA_READ = 2;

    /** Error Code - Malformed or unsupported JPEG stream */
    public static final int ERR_JPEG = 3;

    /** Error Code - Embedding requires a JPEG cover */
    public static final int ERR_COVER_REQUIRED = 4;

    /**
     * Registers the error-code to message-key mappings.
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                F5Plugin.NAMESPACE,
                new int[] {IMAGE_SIZE_INSUFFICIENT, ERR_IMAGE_DATA_READ, ERR_JPEG, ERR_COVER_REQUIRED},
                new String[] {"err.image.insufficientSize", "err.image.read", "err.jpeg.invalid", "err.cover.required"
                });
    }
}
