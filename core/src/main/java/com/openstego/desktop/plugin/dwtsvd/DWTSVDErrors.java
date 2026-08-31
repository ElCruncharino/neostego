/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStegoException;

/**
 * Class to store error codes for the DWT-SVD watermarking plugin
 */
public class DWTSVDErrors {
    /**
     * Error Code - No cover file given
     */
    public static final int ERR_NO_COVER_FILE = 1;

    /**
     * Error Code - Invalid signature file provided
     */
    public static final int ERR_SIG_NOT_VALID = 2;

    /**
     * Error Code - file size not enough to embed watermark
     */
    public static final int ERR_FILE_TOO_SMALL = 3;

    /**
     * Initialize the error code - message key map
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                DWTSVDPlugin.NAMESPACE,
                new int[] {ERR_NO_COVER_FILE, ERR_SIG_NOT_VALID, ERR_FILE_TOO_SMALL},
                new String[] {"err.cover.missing", "err.signature.invalid", "err.file.too.small"});
    }
}
