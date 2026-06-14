/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.PluginCmdLineOption;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.LabelUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Plugin for OpenStego which implements the Least-significant bit algorithm of steganography
 */
public class LSBPlugin extends DHImagePluginTemplate<LSBConfig> {
    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(LSBPlugin.NAMESPACE);

    /**
     * Constant for Namespace to use for this plugin
     */
    public final static String NAMESPACE = "LSB";

    /**
     * Default constructor
     */
    public LSBPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.LSBPluginLabels");
        LSBErrors.init(); // Initialize error codes
    }

    /**
     * Gives the name of the plugin
     *
     * @return Name of the plugin
     */
    @Override
    public String getName() {
        return "LSB";
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
     * Method to embed the message into the cover data
     *
     * @param msg           Message to be embedded
     * @param msgFileName   Name of the message file. If this value is provided, then the filename should be
     *                      embedded in the cover data
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
            try (LSBOutputStream lsbOS = new LSBOutputStream(image, msg.length, msgFileName, this.config)) {
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
     * Method to extract the message file name from the stego data
     *
     * @param stegoData     Stego data containing the message
     * @param stegoFileName Name of the stego file
     * @return Message file name
     * @throws OpenStegoException Processing issues
     */
    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        ImageHolder imgHolder = ImageUtil.byteArrayToImage(stegoData, stegoFileName);
        try (LSBInputStream lsbIS = new LSBInputStream(imgHolder, this.config)) {
            return lsbIS.getDataHeader().getFileName();
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
    }

    /**
     * Method to extract the message from the stego data
     *
     * @param stegoData     Stego data containing the message
     * @param stegoFileName Name of the stego file
     * @param origSigData   Optional signature data file for watermark
     * @return Extracted message
     * @throws OpenStegoException Processing issues
     */
    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        int bytesRead;
        byte[] data;
        LSBDataHeader header;
        ImageHolder imgHolder = ImageUtil.byteArrayToImage(stegoData, stegoFileName);

        try (LSBInputStream lsbIS = new LSBInputStream(imgHolder, this.config)) {
            header = lsbIS.getDataHeader();
            data = new byte[header.getDataLength()];

            bytesRead = lsbIS.read(data, 0, data.length);
            if (bytesRead != data.length) {
                throw new OpenStegoException(null, NAMESPACE, LSBErrors.ERR_IMAGE_DATA_READ);
            }

            return data;
        } catch (IOException ex) {
            throw new OpenStegoException(ex);
        }
    }

    /**
     * Method to get the list of supported file extensions for writing
     *
     * @return List of supported file extensions for writing
     * @throws OpenStegoException Processing issues
     */
    @Override
    public List<String> getWritableFileExtensions() throws OpenStegoException {
        if (writeFormats != null) {
            return writeFormats;
        }

        super.getWritableFileExtensions();
        String format;
        String[] compTypes;
        Iterator<ImageWriter> iter;
        ImageWriteParam writeParam;

        for (int i = writeFormats.size() - 1; i >= 0; i--) {
            format = writeFormats.get(i);
            iter = ImageIO.getImageWritersBySuffix(format);
            while (iter.hasNext()) {
                writeParam = (iter.next()).getDefaultWriteParam();
                try {
                    writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    compTypes = writeParam.getCompressionTypes();
                    if (compTypes.length > 0) {
                        writeParam.setCompressionType(compTypes[0]);
                    }
                } catch (UnsupportedOperationException uoEx) {
                    // Compression not supported
                    break;
                }

                // Only lossless image compression is supported
                if (writeParam.isCompressionLossless()) {
                    break;
                }
                writeFormats.remove(i);
            }
        }

        // Expicilty removing GIF and WBMP formats, as they use unsupported color models
        writeFormats.remove("gif");
        writeFormats.remove("wbmp");
        // Expicilty removing TIF(F) formats, as they are not working correctly - TODO check why
        writeFormats.remove("tif");
        writeFormats.remove("tiff");

        return writeFormats;
    }

    /**
     * Method to declare the plugin-specific command-line options used by this plugin
     *
     * @return List of plugin-specific command-line option descriptors
     */
    @Override
    public List<PluginCmdLineOption> getPluginCmdLineOptions() {
        return Collections.singletonList(
                new PluginCmdLineOption("-b", "--maxBitsUsedPerChannel", "Maximum bits used per color channel", true));
    }

    /**
     * Method to translate parsed plugin-specific command-line values into configuration items
     *
     * @param configMap    Configuration map to populate
     * @param parsedValues Parsed command-line values keyed by option name
     * @throws OpenStegoException Processing issues
     */
    @Override
    public void addPluginConfigValues(Map<String, Object> configMap, Map<String, String> parsedValues) throws OpenStegoException {
        String maxBits = parsedValues.get("-b");
        if (maxBits != null) {
            try {
                configMap.put(LSBConfig.MAX_BITS_USED_PER_CHANNEL, Integer.parseInt(maxBits.trim()));
            } catch (NumberFormatException nfEx) {
                throw new OpenStegoException(nfEx, NAMESPACE, LSBErrors.MAX_BITS_NOT_NUMBER, maxBits);
            }
        }
    }

    /**
     * Method to create default configuration data (specific to this plugin)
     *
     * @return Configuration data
     */
    @Override
    protected LSBConfig createConfig() {
        return new LSBConfig();
    }

    /**
     * Method to get the usage details of the plugin
     *
     * @return Usage details of the plugin
     */
    @Override
    public String getUsage() {
        LSBConfig defaultConfig = new LSBConfig();
        return labelUtil.getString("plugin.usage", defaultConfig.getMaxBitsUsedPerChannel());
    }
}
