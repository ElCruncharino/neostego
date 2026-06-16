/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.template.image;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.WatermarkingPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Template plugin for OpenStego which implements image based steganography for watermarking.
 * <p>
 * Platform-neutral: it works through the {@link ImageCodecRegistry}/{@link PixelImage} abstraction rather than
 * AWT, so the watermarking plugins built on it (DWT-SVD, Dugad, Kim, Xie) run unchanged on desktop and Android.
 */
public abstract class WMImagePluginTemplate extends WatermarkingPlugin<OpenStegoConfig> {

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
     * Method to get the list of supported file extensions for writing.
     * <p>
     * Unlike data hiding (which requires a lossless carrier), a robust watermark is designed to survive lossy
     * re-compression, so JPEG is an additional valid output format on top of the codec's lossless formats.
     *
     * @return List of supported file extensions for writing
     * @throws OpenStegoException Processing issues
     */
    @Override
    public List<String> getWritableFileExtensions() throws OpenStegoException {
        List<String> formats = new ArrayList<>(ImageCodecRegistry.get().getWritableFormats());
        if (!formats.contains("jpeg")) {
            formats.add("jpeg");
        }
        if (!formats.contains("jpg")) {
            formats.add("jpg");
        }
        return formats;
    }

    /**
     * Method to create default configuration data (specific to this plugin)
     *
     * @return Configuration data
     */
    @Override
    protected OpenStegoConfig createConfig() {
        return new OpenStegoConfig();
    }
}
