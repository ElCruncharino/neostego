/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;

/**
 * Computes a visual difference between a cover image and its stegged counterpart.
 * <p>
 * Shared by {@code DHImagePluginTemplate} (data hiding) and {@code WMImagePluginTemplate} (watermarking), which
 * have no common ancestor but need identical difference-image behavior.
 */
public final class ImageDiffUtil {

    private ImageDiffUtil() {}

    /**
     * Computes the difference between the original cover file and the stegged file. The difference image
     * highlights, per color channel, where the stego file differs from the cover.
     *
     * @param stegoData     Stego data containing the embedded data
     * @param stegoFileName Name of the stego file
     * @param coverData     Original cover data
     * @param coverFileName Name of the cover file
     * @param diffFileName  Name of the output difference file
     * @return Difference data
     * @throws OpenStegoException Processing issues
     */
    public static byte[] getDiff(
            byte[] stegoData, String stegoFileName, byte[] coverData, String coverFileName, String diffFileName)
            throws OpenStegoException {
        PixelImage stegoImage = ImageCodecRegistry.get().decode(stegoData, stegoFileName);
        PixelImage coverImage = ImageCodecRegistry.get().decode(coverData, coverFileName);

        int width = coverImage.getWidth();
        int height = coverImage.getHeight();
        if (stegoImage.getWidth() != width || stegoImage.getHeight() != height) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.IMAGE_FILE_INVALID);
        }

        // Compute the per-channel absolute difference in place (reuse the cover image as the target)
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int cp = coverImage.getRGB(x, y);
                int sp = stegoImage.getRGB(x, y);
                int dr = Math.abs(((cp >> 16) & 0xFF) - ((sp >> 16) & 0xFF));
                int dg = Math.abs(((cp >> 8) & 0xFF) - ((sp >> 8) & 0xFF));
                int db = Math.abs((cp & 0xFF) - (sp & 0xFF));
                coverImage.setRGB(x, y, (dr << 16) | (dg << 8) | db);
            }
        }

        return ImageCodecRegistry.get().encode(coverImage, diffFileName);
    }
}
