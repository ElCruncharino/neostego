/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.wavlsb;

import com.openstego.desktop.OpenStegoException;

/**
 * Error codes for the WAV LSB audio plugin.
 */
public class WavLSBErrors {
    /** Error Code - Cover audio capacity insufficient for the data */
    public static final int AUDIO_SIZE_INSUFFICIENT = 1;

    /** Error Code - Not a valid RIFF/WAVE file */
    public static final int INVALID_WAV_FILE = 2;

    /** Error Code - WAV is not uncompressed integer PCM */
    public static final int UNSUPPORTED_WAV_FORMAT = 3;

    /** Error Code - No cover file was supplied */
    public static final int NO_COVER_FILE = 4;

    /** Error Code - Error reading embedded audio data */
    public static final int ERR_AUDIO_DATA_READ = 5;

    /**
     * Initialize the error code - message key map
     */
    public static void init() {
        OpenStegoException.addErrorCodes(
                WavLSBPlugin.NAMESPACE,
                new int[] {AUDIO_SIZE_INSUFFICIENT, INVALID_WAV_FILE, UNSUPPORTED_WAV_FORMAT, NO_COVER_FILE, ERR_AUDIO_DATA_READ},
                new String[] {
                    "err.audio.insufficientSize",
                    "err.audio.invalidWav",
                    "err.audio.unsupportedFormat",
                    "err.audio.noCoverFile",
                    "err.audio.read"
                });
    }
}
