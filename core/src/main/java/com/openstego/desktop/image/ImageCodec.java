/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image;

import com.openstego.desktop.OpenStegoException;
import java.util.List;

/**
 * Platform-specific image encoding/decoding for the steganography core.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}, so each platform (desktop using
 * AWT/ImageIO, Android using Bitmap, etc.) provides its own backend without the core code depending on
 * it. See {@link ImageCodecRegistry}.
 */
public interface ImageCodec {
    /**
     * Decodes image bytes into a mutable RGB {@link PixelImage}.
     *
     * @param data     Encoded image bytes
     * @param fileName Original file name (used for diagnostics / format hints), may be null
     * @return Decoded mutable image
     * @throws OpenStegoException Processing issues
     */
    PixelImage decode(byte[] data, String fileName) throws OpenStegoException;

    /**
     * Encodes a {@link PixelImage} into image bytes. The output format is derived from the file name
     * extension (defaulting to PNG when not provided).
     *
     * @param image    Image to encode
     * @param fileName Target file name (used to choose the format), may be null for default PNG
     * @return Encoded image bytes
     * @throws OpenStegoException Processing issues
     */
    byte[] encode(PixelImage image, String fileName) throws OpenStegoException;

    /**
     * Creates a new random-noise image large enough to hold the given number of pixels (used when no
     * cover image is supplied).
     *
     * @param numOfPixels Minimum number of pixels required
     * @return Random image
     * @throws OpenStegoException Processing issues
     */
    PixelImage createRandomImage(int numOfPixels) throws OpenStegoException;

    /**
     * Sets a JPEG output quality override for subsequent {@link #encode} calls that produce a JPEG, or
     * clears it when {@code quality} is null. Both platforms already carry a quality knob for
     * watermarking's JPEG output ({@code ImageUtil.setJpegQuality} on desktop, {@code jpegQuality} on
     * Android); this exposes that existing mechanism through the platform-agnostic interface so
     * core-module code (robust-mode embed verification, simulating a specific recompression) can drive
     * it without depending on either platform module directly. Codecs that never write JPEG may no-op.
     *
     * @param quality Quality in [0.0, 1.0], or null to clear the override
     */
    default void setJpegQuality(Float quality) {
        // no-op by default
    }

    /**
     * @return List of file extensions that can be read as cover images
     */
    List<String> getReadableFormats();

    /**
     * @return List of file extensions that can be written as stego images
     */
    List<String> getWritableFormats();
}
