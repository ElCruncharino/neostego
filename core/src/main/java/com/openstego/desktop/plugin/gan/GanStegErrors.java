/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.gan;

import com.openstego.desktop.OpenStegoException;

/**
 * Error codes for the experimental GAN-based steganography plugin.
 */
public class GanStegErrors {

    /** Error Code - No cover file given */
    public static final int ERR_COVER_REQUIRED = 1;

    /** Error Code - Message (header + payload) is too long for this plugin's fixed error-correction block */
    public static final int ERR_MESSAGE_TOO_LONG = 2;

    /** Error Code - Cover image is too small for the underlying network's minimum tile size */
    public static final int ERR_IMAGE_TOO_SMALL = 3;

    /** Error Code - No candidate message survived error correction and majority voting */
    public static final int ERR_DECODE_FAILED = 4;

    /** Error Code - The ONNX runtime or model resources could not be loaded */
    public static final int ERR_MODEL_UNAVAILABLE = 5;

    /**
     * Initialize the error code - message key map
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                GanStegPlugin.NAMESPACE,
                new int[] {
                    ERR_COVER_REQUIRED, ERR_MESSAGE_TOO_LONG, ERR_IMAGE_TOO_SMALL, ERR_DECODE_FAILED,
                    ERR_MODEL_UNAVAILABLE
                },
                new String[] {
                    "err.cover.required",
                    "err.message.tooLong",
                    "err.image.tooSmall",
                    "err.decode.failed",
                    "err.model.unavailable"
                });
    }
}
