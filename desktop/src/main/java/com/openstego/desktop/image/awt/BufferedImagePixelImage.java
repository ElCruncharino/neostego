/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.awt;

import com.openstego.desktop.image.PixelImage;
import java.awt.image.BufferedImage;

/**
 * {@link PixelImage} backed by an AWT {@link BufferedImage} (desktop platform).
 */
public class BufferedImagePixelImage implements PixelImage {
    private final BufferedImage image;
    private byte[] iccProfile;

    /**
     * @param image Backing image (expected to be of type {@link BufferedImage#TYPE_INT_ARGB} so that
     *              alpha is preserved; embedding only modifies the RGB channels)
     */
    public BufferedImagePixelImage(BufferedImage image) {
        this.image = image;
    }

    /**
     * @return The backing AWT image
     */
    public BufferedImage getBufferedImage() {
        return this.image;
    }

    /**
     * @return Raw ICC profile bytes captured from the cover, carried through so the stego output keeps the
     *         cover's colour profile (upstream issue #62). Null when the cover had no embedded profile.
     */
    public byte[] getIccProfile() {
        return this.iccProfile;
    }

    /**
     * @param iccProfile ICC profile bytes captured from the cover (may be null)
     */
    public void setIccProfile(byte[] iccProfile) {
        this.iccProfile = iccProfile;
    }

    @Override
    public int getWidth() {
        return this.image.getWidth();
    }

    @Override
    public int getHeight() {
        return this.image.getHeight();
    }

    @Override
    public int getRGB(int x, int y) {
        return this.image.getRGB(x, y);
    }

    @Override
    public void setRGB(int x, int y, int rgb) {
        this.image.setRGB(x, y, rgb);
    }
}
