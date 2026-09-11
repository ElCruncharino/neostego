/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

/**
 * Abstract class for stego plugins for OpenStego purpose of which is watermarking. It implements few methods which are
 * specific for watermarking, and provides dummy implementation for the methods which are specific to data hiding
 * purposes so that sub-class does not need to implement them
 *
 * @see DataHidingPlugin
 */
public abstract class WatermarkingPlugin<C extends OpenStegoConfig> extends OpenStegoPlugin<C> {
    // ------------- Metadata Methods -------------

    /**
     * Gives the purpose of the plugin. This implementation is always Watermarking
     *
     * @return Purpose of the plugin
     */
    @Override
    public final Purpose getPurpose() {
        return Purpose.WATERMARKING;
    }

    // ------------- Core Stego Methods -------------

    /**
     * Method to extract the message file name from the stego data. This implementation returns <code>null</code> as
     * this class is for watermarking plugins only
     *
     * @param stegoData     Stego data containing the message
     * @param stegoFileName Name of the stego file
     * @return Message file name
     */
    @Override
    public final String extractMsgFileName(byte[] stegoData, String stegoFileName) {
        return null;
    }

    /**
     * Method to get correlation value which above which it can be considered that watermark strength is high (default
     * to 0.5 which is safe for general watermarking)
     *
     * @return High watermark
     */
    @Override
    public double getHighWatermarkLevel() {
        return 0.5;
    }

    /**
     * Method to get correlation value which below which it can be considered that watermark strength is low (default to
     * 0.2 which is safe for general watermarking)
     *
     * @return Low watermark
     */
    @Override
    public double getLowWatermarkLevel() {
        return 0.2;
    }
}
