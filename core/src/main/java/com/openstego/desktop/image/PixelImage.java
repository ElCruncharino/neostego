/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image;

/**
 * Platform-independent, mutable RGB image abstraction used by the steganography algorithms.
 * <p>
 * It exposes only the per-pixel access the embedding/extraction code needs, so the core algorithms
 * have no dependency on any particular imaging library (AWT on the desktop, Bitmap on Android, etc.).
 * Pixel values are packed as <code>0xRRGGBB</code> in the low 24 bits (alpha is ignored).
 */
public interface PixelImage {
    /**
     * @return Image width in pixels
     */
    int getWidth();

    /**
     * @return Image height in pixels
     */
    int getHeight();

    /**
     * Returns the packed RGB value (0xRRGGBB) of the pixel at the given coordinates.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return Packed RGB value
     */
    int getRGB(int x, int y);

    /**
     * Sets the packed RGB value (0xRRGGBB) of the pixel at the given coordinates.
     *
     * @param x   X coordinate
     * @param y   Y coordinate
     * @param rgb Packed RGB value
     */
    void setRGB(int x, int y, int rgb);
}
