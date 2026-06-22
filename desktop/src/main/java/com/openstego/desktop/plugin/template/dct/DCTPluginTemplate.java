/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.template.dct;

import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.LabelUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Template plugin for OpenStego which implements the DCT based steganography for images (transfer domain)
 */
public abstract class DCTPluginTemplate extends OpenStegoPlugin<DCTConfig> {
    /**
     * Constant for Namespace to use for this plugin
     */
    public static final String NAMESPACE = "DCTTEMPLATE";

    /**
     * Static list of supported read formats
     */
    private static List<String> readFormats = null;

    /**
     * Static list of supported write formats
     */
    private static List<String> writeFormats = null;

    static {
        LabelUtil.addNamespace(NAMESPACE, "i18n.DCTPluginTemplateLabels");
        DCTErrors.init(); // Initialize error codes
    }

    /**
     * Method to get the list of supported file extensions for reading
     *
     * @return List of supported file extensions for reading
     */
    @Override
    public List<String> getReadableFileExtensions() {
        if (readFormats != null) {
            return readFormats;
        }

        String format;
        String[] formats;
        List<String> formatList = new ArrayList<>();

        formats = ImageIO.getReaderFormatNames();
        for (String s : formats) {
            format = s.toLowerCase();
            if (format.contains("jpeg") && format.contains("2000")) {
                format = "jp2";
            }
            if (!formatList.contains(format)) {
                formatList.add(format);
            }
        }

        Collections.sort(formatList);
        readFormats = formatList;
        return readFormats;
    }

    /**
     * Method to get the list of supported file extensions for writing
     *
     * @return List of supported file extensions for writing
     */
    @Override
    public List<String> getWritableFileExtensions() {
        if (writeFormats != null) {
            return writeFormats;
        }

        String format;
        String[] formats;
        List<String> formatList = new ArrayList<>();

        formats = ImageIO.getWriterFormatNames();
        for (String s : formats) {
            format = s.toLowerCase();
            if (format.contains("jpeg") && format.contains("2000")) {
                format = "jp2";
            }
            if (!formatList.contains(format)) {
                formatList.add(format);
            }
        }

        Collections.sort(formatList);
        writeFormats = formatList;
        return writeFormats;
    }

    /**
     * Method to create default configuration data (specific to this plugin)
     *
     * @return Configuration data
     */
    @Override
    protected DCTConfig createConfig() {
        return new DCTConfig();
    }
}
