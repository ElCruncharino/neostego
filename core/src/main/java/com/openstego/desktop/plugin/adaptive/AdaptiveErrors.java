/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoException;

/**
 * Error codes for the content-adaptive (HILL+STC) plugin.
 */
public class AdaptiveErrors {

    /** Error Code - Image size insufficient for the data */
    public static final int IMAGE_SIZE_INSUFFICIENT = 1;

    /** Error Code - Error while reading the embedded data */
    public static final int ERR_IMAGE_DATA_READ = 2;

    /**
     * Registers the error-code to message-key mappings.
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                AdaptiveImagePlugin.NAMESPACE,
                new int[] {IMAGE_SIZE_INSUFFICIENT, ERR_IMAGE_DATA_READ},
                new String[] {"err.image.insufficientSize", "err.image.read"});
    }
}
