/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;

/**
 * Class to store configuration data for LSB plugin
 */
public class LSBConfig extends OpenStegoConfig {
    /**
     * Key string for configuration item - maxBitsUsedPerChannel.
     * <p>
     * Maximum bits to use per color channel. Allowing for higher number here might degrade the quality of the image in
     * case the data size is big.
     */
    public static final String MAX_BITS_USED_PER_CHANNEL = "maxBitsUsedPerChannel";

    /**
     * Maximum bits to use per color channel. Allowing for higher number here might degrade the quality
     * of the image in case the data size is big.
     */
    private int maxBitsUsedPerChannel = 3;

    /**
     * Processes a configuration item.
     *
     * @param key   Configuration item key
     * @param value Configuration item value
     */
    @Override
    protected void processConfigItem(String key, Object value) throws OpenStegoException {
        super.processConfigItem(key, value);
        if (key.equals(MAX_BITS_USED_PER_CHANNEL)) {
            assert value instanceof Integer;
            this.maxBitsUsedPerChannel = (int) value;
            if (this.maxBitsUsedPerChannel < 1 || this.maxBitsUsedPerChannel > 8) {
                throw new OpenStegoException(null, LSBPlugin.NAMESPACE, LSBErrors.MAX_BITS_NOT_IN_RANGE, value);
            }
        }
    }

    /**
     * Get method for configuration item - maxBitsUsedPerChannel
     *
     * @return maxBitsUsedPerChannel
     */
    public int getMaxBitsUsedPerChannel() {
        return this.maxBitsUsedPerChannel;
    }

    /**
     * Set method for configuration item - maxBitsUsedPerChannel
     *
     * @param maxBitsUsedPerChannel Value to be set
     */
    public void setMaxBitsUsedPerChannel(int maxBitsUsedPerChannel) {
        this.maxBitsUsedPerChannel = maxBitsUsedPerChannel;
    }
}
