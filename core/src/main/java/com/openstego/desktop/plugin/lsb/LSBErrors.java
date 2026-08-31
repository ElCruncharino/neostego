/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStegoException;

/**
 * Class to store error codes for LSB plugin
 */
public class LSBErrors {
    /**
     * Error Code - Error while reading image data
     */
    public static final int ERR_IMAGE_DATA_READ = 1;

    /**
     * Error Code - Null value provided for image
     */
    public static final int NULL_IMAGE_ARGUMENT = 2;

    /**
     * Error Code - Image size insufficient for data
     */
    public static final int IMAGE_SIZE_INSUFFICIENT = 3;

    /**
     * Error Code - maxBitsUsedPerChannel is not a number
     */
    public static final int MAX_BITS_NOT_NUMBER = 4;

    /**
     * Error Code - maxBitsUsedPerChannel is not in valid range
     */
    public static final int MAX_BITS_NOT_IN_RANGE = 5;

    /**
     * Error Code - Invalid stego header data
     */
    public static final int INVALID_STEGO_HEADER = 6;

    /**
     * Error Code - Invalid image header version
     */
    public static final int INVALID_HEADER_VERSION = 7;

    /**
     * Initialize the error code - message key map
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                LSBPlugin.NAMESPACE,
                new int[] {
                    ERR_IMAGE_DATA_READ,
                    NULL_IMAGE_ARGUMENT,
                    IMAGE_SIZE_INSUFFICIENT,
                    MAX_BITS_NOT_NUMBER,
                    MAX_BITS_NOT_IN_RANGE,
                    INVALID_STEGO_HEADER,
                    INVALID_HEADER_VERSION
                },
                new String[] {
                    "err.image.read",
                    "err.image.arg.nullValue",
                    "err.image.insufficientSize",
                    "err.config.maxBitsUsedPerChannel.notNumber",
                    "err.config.maxBitsUsedPerChannel.notInRange",
                    "err.invalidHeaderStamp",
                    "err.invalidHeaderVersion"
                });
    }
}
