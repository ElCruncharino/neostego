/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.plugin.template.image;

import com.openstego.desktop.DataHidingPlugin;
import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;

import java.util.List;

/**
 * Template plugin for OpenStego which implements image based steganography for data hiding
 */
public abstract class DHImagePluginTemplate<C extends OpenStegoConfig> extends DataHidingPlugin<C> {

    /**
     * Method to get difference between original cover file and the stegged file. The difference image
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
    @Override
    public final byte[] getDiff(byte[] stegoData, String stegoFileName, byte[] coverData, String coverFileName, String diffFileName)
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

    /**
     * Method to get the list of supported file extensions for reading
     *
     * @return List of supported file extensions for reading
     */
    @Override
    public List<String> getReadableFileExtensions() {
        return ImageCodecRegistry.get().getReadableFormats();
    }

    /**
     * Method to get the list of supported file extensions for writing
     *
     * @return List of supported file extensions for writing
     * @throws OpenStegoException Processing issues
     */
    @Override
    public List<String> getWritableFileExtensions() throws OpenStegoException {
        return ImageCodecRegistry.get().getWritableFormats();
    }

}
