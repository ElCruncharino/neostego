/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.plugin.randlsb;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.plugin.lsb.LSBConfig;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.LabelUtil;

import java.io.IOException;

/**
 * Plugin for OpenStego which implements Random LSB <em>matching</em> (&plusmn;1) steganography.
 * <p>
 * This is a more detection-resistant variant of {@link RandomLSBPlugin}: it embeds the same bits at
 * the same password-chosen positions, but sets each target least-significant bit by adding or
 * subtracting one rather than overwriting it. This avoids the structural artifacts that RS,
 * Sample-Pair and Chi-square steganalysis exploit. Extraction is identical to Random LSB.
 */
public class RandomLSBMatchPlugin extends RandomLSBPlugin {
    /**
     * Constant for Namespace to use for this plugin
     */
    public final static String NAMESPACE = "RandomLSBMatch";

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /**
     * Default constructor
     */
    public RandomLSBMatchPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.RandomLSBMatchPluginLabels");
    }

    /**
     * Gives the name of the plugin
     *
     * @return Name of the plugin
     */
    @Override
    public String getName() {
        return "RandomLSBMatch";
    }

    /**
     * Gives a short description of the plugin
     *
     * @return Short description of the plugin
     */
    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    /**
     * Method to embed the message into the cover data using LSB matching
     *
     * @param msg           Message to be embedded
     * @param msgFileName   Name of the message file
     * @param cover         Cover data into which message needs to be embedded
     * @param coverFileName Name of the cover file
     * @param stegoFileName Name of the output stego file
     * @return Stego data containing the message
     * @throws OpenStegoException Processing issues
     */
    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName) throws OpenStegoException {
        int numOfPixels;
        ImageHolder image;

        try {
            // Generate random image, if input image is not provided
            if (cover == null) {
                numOfPixels = (int) (LSBDataHeader.getMaxHeaderSize() * 8 / 3.0);
                numOfPixels += (int) (msg.length * 8 / (3.0 * this.config.getMaxBitsUsedPerChannel()));
                image = ImageUtil.generateRandomImage(numOfPixels);
            } else {
                image = ImageUtil.byteArrayToImage(cover, coverFileName);
            }
            try (RandomLSBMatchOutputStream lsbOS = new RandomLSBMatchOutputStream(image, msg.length, msgFileName, this.config)) {
                lsbOS.write(msg);
                lsbOS.flush();
                image = lsbOS.getImage();
            }

            return ImageUtil.imageToByteArray(image, stegoFileName, this);
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
    }

    /**
     * Method to create default configuration data (specific to this plugin). Matching is only well
     * defined for the least-significant bit, so this defaults to a single bit per channel (also the
     * most detection-resistant setting).
     *
     * @return Configuration data
     */
    @Override
    protected LSBConfig createConfig() {
        LSBConfig config = new LSBConfig();
        config.setMaxBitsUsedPerChannel(1);
        return config;
    }

    /**
     * Method to get the usage details of the plugin
     *
     * @return Usage details of the plugin
     */
    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }
}
