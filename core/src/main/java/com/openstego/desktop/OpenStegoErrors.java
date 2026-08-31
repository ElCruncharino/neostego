/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

import static com.openstego.desktop.OpenStego.NAMESPACE;

/**
 * Custom exception class for OpenStego
 */
public class OpenStegoErrors {
    /**
     * Error Code - Invalid password
     */
    public static final int INVALID_PASSWORD = 1;

    /**
     * Error Code - Invalid value for useCompression
     */
    public static final int INVALID_USE_COMPR_VALUE = 2;

    /**
     * Error Code - Invalid value for useEncryption
     */
    public static final int INVALID_USE_ENCRYPT_VALUE = 3;

    /**
     * Error Code - Invalid key name
     */
    public static final int INVALID_KEY_NAME = 4;

    /**
     * Error Code - Corrupt Data
     */
    public static final int CORRUPT_DATA = 5;

    /**
     * Error Code - No valid plugin
     */
    public static final int NO_VALID_PLUGIN = 6;

    /**
     * Error Code - Image type invalid
     */
    public static final int IMAGE_TYPE_INVALID = 7;

    /**
     * Error Code - Image file invalid
     */
    public static final int IMAGE_FILE_INVALID = 8;

    /**
     * Error Code - No plugin specified
     */
    public static final int NO_PLUGIN_SPECIFIED = 9;

    /**
     * Error Code - Plugin does not support watermarking
     */
    public static final int PLUGIN_DOES_NOT_SUPPORT_WM = 10;

    /**
     * Error Code - Plugin not found
     */
    public static final int PLUGIN_NOT_FOUND = 11;

    /**
     * Error Code - Image sizes mismatch
     */
    public static final int IMAGE_SIZE_MISMATCH = 12;

    /**
     * Error Code - Plugin does not support data hiding
     */
    public static final int PLUGIN_DOES_NOT_SUPPORT_DH = 14;

    /**
     * Error Code - Password is mandatory for 'gensig' operation
     */
    public static final int PWD_MANDATORY_FOR_GENSIG = 15;

    /**
     * Error Code - Invalid key name
     */
    public static final int INVALID_CRYPT_ALGO = 16;

    /**
     * Error Code - Invalid integer in user preference file
     */
    public static final int USERPREF_INVALID_INT = 17;

    /**
     * Error Code - Invalid float in user preference file
     */
    public static final int USERPREF_INVALID_FLOAT = 18;

    /**
     * Error Code - Invalid boolean in user preference file
     */
    public static final int USERPREF_INVALID_BOOL = 19;

    /**
     * Error Code - Multi-cover split needs at least two cover files
     */
    public static final int SPLIT_REQUIRES_MULTIPLE_COVERS = 20;

    /**
     * Error Code - Multi-cover split extraction needs at least two stego files
     */
    public static final int SPLIT_REQUIRES_MULTIPLE_PARTS = 21;

    /**
     * Error Code - The provided covers cannot hold the payload when splitting
     */
    public static final int SPLIT_INSUFFICIENT_CAPACITY = 22;

    /**
     * Error Code - A split part's manifest is corrupt or not a multi-cover part
     */
    public static final int SPLIT_MANIFEST_CORRUPT = 23;

    /**
     * Error Code - Not all parts of a multi-cover split were provided
     */
    public static final int SPLIT_MANIFEST_INCOMPLETE = 24;

    /**
     * Error Code - Provided stego files belong to different splits
     */
    public static final int SPLIT_MANIFEST_MISMATCH = 25;

    /**
     * Error Code - Multi-cover split embedding needs the output to be a directory
     */
    public static final int SPLIT_REQUIRES_OUTPUT_DIR = 26;

    /**
     * Error Code - The selected algorithm does not support multi-cover splitting
     */
    public static final int SPLIT_NOT_SUPPORTED = 27;

    /**
     * Error Code - A password is required but none was supplied and no interactive terminal is available
     */
    public static final int PASSWORD_REQUIRED = 28;

    /**
     * Error Code - The specified cover file could not be found
     */
    public static final int COVER_FILE_NOT_FOUND = 29;

    /**
     * Error Code - A required command-line option is missing (usage error)
     */
    public static final int MISSING_REQUIRED_OPTION = 30;

    /**
     * Initialize the error code - message key map
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                NAMESPACE,
                new int[] {
                    INVALID_PASSWORD,
                    INVALID_USE_COMPR_VALUE,
                    INVALID_USE_ENCRYPT_VALUE,
                    INVALID_KEY_NAME,
                    INVALID_CRYPT_ALGO,
                    USERPREF_INVALID_INT,
                    USERPREF_INVALID_FLOAT,
                    USERPREF_INVALID_BOOL,
                    CORRUPT_DATA,
                    NO_VALID_PLUGIN,
                    IMAGE_TYPE_INVALID,
                    IMAGE_FILE_INVALID,
                    NO_PLUGIN_SPECIFIED,
                    PLUGIN_DOES_NOT_SUPPORT_WM,
                    PLUGIN_DOES_NOT_SUPPORT_DH,
                    PLUGIN_NOT_FOUND,
                    IMAGE_SIZE_MISMATCH,
                    PWD_MANDATORY_FOR_GENSIG,
                    SPLIT_REQUIRES_MULTIPLE_COVERS,
                    SPLIT_REQUIRES_MULTIPLE_PARTS,
                    SPLIT_INSUFFICIENT_CAPACITY,
                    SPLIT_MANIFEST_CORRUPT,
                    SPLIT_MANIFEST_INCOMPLETE,
                    SPLIT_MANIFEST_MISMATCH,
                    SPLIT_REQUIRES_OUTPUT_DIR,
                    SPLIT_NOT_SUPPORTED,
                    PASSWORD_REQUIRED,
                    COVER_FILE_NOT_FOUND,
                    MISSING_REQUIRED_OPTION
                },
                new String[] {
                    "err.config.password.invalid",
                    "err.config.useCompression.invalid",
                    "err.config.useEncryption.invalid",
                    "err.config.invalidKey",
                    "err.config.invalidCryptAlgo",
                    "err.userpref.valueNotInteger",
                    "err.userpref.valueNotFloat",
                    "err.userpref.valueNotBoolean",
                    "err.corruptData",
                    "err.noValidPlugin",
                    "err.image.type.invalid",
                    "err.image.file.invalid",
                    "err.plugin.notSpecified",
                    "err.plugin.wmNotSupported",
                    "err.plugin.dhNotSupported",
                    "err.plugin.notFound",
                    "err.image.size.mismatch",
                    "err.gensig.pwdMandatory",
                    "err.split.requiresMultipleCovers",
                    "err.split.requiresMultipleParts",
                    "err.split.insufficientCapacity",
                    "err.split.manifestCorrupt",
                    "err.split.manifestIncomplete",
                    "err.split.manifestMismatch",
                    "err.split.requiresOutputDir",
                    "err.split.notSupported",
                    "err.passwordRequired",
                    "cmd.msg.coverFileNotFound",
                    "err.cmd.missingRequiredOption"
                });
    }
}
