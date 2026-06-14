/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
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
     * @return List of file extensions that can be read as cover images
     */
    List<String> getReadableFormats();

    /**
     * @return List of file extensions that can be written as stego images
     */
    List<String> getWritableFormats();
}
