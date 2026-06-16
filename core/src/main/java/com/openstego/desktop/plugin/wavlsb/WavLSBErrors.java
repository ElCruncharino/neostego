/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
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
        OpenStegoException.addErrorCode(WavLSBPlugin.NAMESPACE, AUDIO_SIZE_INSUFFICIENT, "err.audio.insufficientSize");
        OpenStegoException.addErrorCode(WavLSBPlugin.NAMESPACE, INVALID_WAV_FILE, "err.audio.invalidWav");
        OpenStegoException.addErrorCode(WavLSBPlugin.NAMESPACE, UNSUPPORTED_WAV_FORMAT, "err.audio.unsupportedFormat");
        OpenStegoException.addErrorCode(WavLSBPlugin.NAMESPACE, NO_COVER_FILE, "err.audio.noCoverFile");
        OpenStegoException.addErrorCode(WavLSBPlugin.NAMESPACE, ERR_AUDIO_DATA_READ, "err.audio.read");
    }
}
