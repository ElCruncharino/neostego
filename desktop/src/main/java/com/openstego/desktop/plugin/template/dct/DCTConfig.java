/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.template.dct;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;

/**
 * Class to store configuration data for DCT plugin template
 */
public class DCTConfig extends OpenStegoConfig {
    /**
     * Key string for configuration item - imageFileExtension
     * <p>
     * Image file extension for the output file
     */
    public static final String IMAGE_FILE_EXTENSION = "imageFileExtension";

    /**
     * Image file extension to use for writing
     */
    private String imageFileExtension = "png";

    /**
     * Processes a configuration item.
     *
     * @param key   Configuration item key
     * @param value Configuration item value
     */
    @Override
    protected void processConfigItem(String key, Object value) throws OpenStegoException {
        super.processConfigItem(key, value);
        if (key.equals(IMAGE_FILE_EXTENSION)) {
            assert value instanceof String;
            this.imageFileExtension = (String) value;
        }
    }

    /**
     * Get method for configuration item - imageFileExtension
     *
     * @return imageFileExtension
     */
    @SuppressWarnings("unused")
    public String getImageFileExtension() {
        return this.imageFileExtension;
    }

    /**
     * Set method for configuration item - imageFileExtension
     *
     * @param imageFileExtension Value to be set
     */
    @SuppressWarnings("unused")
    public void setImageFileExtension(String imageFileExtension) {
        this.imageFileExtension = imageFileExtension;
    }
}
