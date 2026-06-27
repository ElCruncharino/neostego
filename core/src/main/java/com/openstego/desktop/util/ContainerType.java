/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

/**
 * Coarse classification of a stego file by its container magic bytes. Extraction is
 * algorithm-agnostic - the stego file records nothing about which plugin wrote it - so the only
 * signal available up front is the container format. This three-way split is enough to route an
 * extraction attempt to the plugins that could possibly decode it: JPEG-domain plugins handle
 * {@link #JPEG}, the WAV plugin handles {@link #WAVE}, and the spatial image plugins handle
 * {@link #OTHER} (PNG/BMP/WebP/... and anything unrecognized, which is left for them to reject
 * cleanly).
 */
public enum ContainerType {
    /** JPEG (starts with the SOI marker {@code FF D8}). */
    JPEG,

    /** RIFF/WAVE audio container. */
    WAVE,

    /** Any other (PNG, BMP, WebP, ...) or unrecognized input. */
    OTHER;

    /**
     * Classifies the given stego bytes by their leading magic bytes.
     *
     * @param data Stego file bytes (may be {@code null})
     * @return The detected container type; {@link #OTHER} when the bytes match no known signature
     */
    public static ContainerType detect(byte[] data) {
        if (data == null) {
            return OTHER;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return JPEG;
        }
        if (data.length >= 12
                && data[0] == 'R'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == 'F'
                && data[8] == 'W'
                && data[9] == 'A'
                && data[10] == 'V'
                && data[11] == 'E') {
            return WAVE;
        }
        return OTHER;
    }
}
