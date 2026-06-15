/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.randlsb;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.plugin.lsb.LSBConfig;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.lsb.LSBErrors;
import com.openstego.desktop.plugin.lsb.LSBPlugin;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.util.StringUtil;

import java.io.OutputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * OutputStream to embed data into image
 */
public class RandomLSBOutputStream extends OutputStream {
    /**
     * Output Image data
     */
    private final PixelImage image;

    /**
     * Number of bits used per color channel
     */
    private int channelBitsUsed;

    /**
     * Length of the data
     */
    private final int dataLength;

    /**
     * Name of the source data file
     */
    private final String fileName;

    /**
     * Width of the image
     */
    private final int imgWidth;

    /**
     * Height of the image
     */
    private final int imgHeight;

    /**
     * Configuration data
     */
    private final OpenStegoConfig config;

    /**
     * Array for bits in the image
     */
    private final Set<String> bitWritten = new HashSet<>();

    /**
     * Random number generator
     */
    private final Random rand;

    /**
     * Default constructor
     *
     * @param image      Source image into which data will be embedded
     * @param dataLength Length of the data that would be written to the image
     * @param fileName   Name of the source data file
     * @param config     Configuration data to use while writing
     * @throws OpenStegoException Processing issues
     */
    public RandomLSBOutputStream(PixelImage image, int dataLength, String fileName, OpenStegoConfig config) throws OpenStegoException {
        if (image == null) {
            throw new OpenStegoException(null, LSBPlugin.NAMESPACE, LSBErrors.NULL_IMAGE_ARGUMENT);
        }

        this.dataLength = dataLength;
        this.imgWidth = image.getWidth();
        this.imgHeight = image.getHeight();
        this.config = config;
        // The codec provides a mutable RGB image; embed directly into it
        this.image = image;

        this.channelBitsUsed = 1;
        this.fileName = fileName;

        // Initialize random number generator with seed generated using password
        this.rand = new Random(StringUtil.passwordHash(config.getPassword()));
        writeHeader();
    }

    /**
     * Method to write header data to stream
     *
     * @throws OpenStegoException Processing issues
     */
    private void writeHeader() throws OpenStegoException {
        int channelBits = 1;
        int noOfPixels;
        int headerSize;
        LSBDataHeader header;

        try {
            noOfPixels = this.imgWidth * this.imgHeight;
            header = new LSBDataHeader(this.dataLength, channelBits, this.fileName, this.config);
            headerSize = header.getHeaderSize();

            while (true) {
                if ((noOfPixels * 3 * channelBits) / 8.0 < (headerSize + this.dataLength)) {
                    channelBits++;
                    if (channelBits > ((LSBConfig) this.config).getMaxBitsUsedPerChannel()) {
                        throw new OpenStegoException(null, LSBPlugin.NAMESPACE, LSBErrors.IMAGE_SIZE_INSUFFICIENT);
                    }
                } else {
                    break;
                }
            }

            // Update channelBitsUsed in the header, and write to image
            header.setChannelBitsUsed(channelBits);

            write(header.getHeaderData());
            this.channelBitsUsed = channelBits;
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    /**
     * Implementation of <code>OutputStream.write(int)</code> method
     *
     * @param data Byte to be written
     */
    @Override
    public void write(int data) {
        boolean bitValue;
        int x;
        int y;
        int channel;
        int bit;
        String key;

        for (int i = 0; i < 8; i++) {
            bitValue = ((data >> (7 - i)) & 0x1) == 0x1;

            do {
                x = this.rand.nextInt(this.imgWidth);
                y = this.rand.nextInt(this.imgHeight);
                channel = this.rand.nextInt(3);
                bit = this.rand.nextInt(this.channelBitsUsed);
                key = x + "_" + y + "_" + channel + "_" + bit;
            } while (this.bitWritten.contains(key));
            this.bitWritten.add(key);

            setPixelBit(x, y, channel, bit, bitValue);
        }
    }

    /**
     * Get the image containing the embedded data. Ideally, this should be called after the stream is closed.
     *
     * @return Image data
     */
    public PixelImage getImage() {
        return this.image;
    }

    /**
     * Sets the pixel bit at the given location to the new value.
     *
     * @param x        The x position of the pixel
     * @param y        The y position of the pixel
     * @param channel  The color channel of the bit
     * @param bit      The position of the bit
     * @param bitValue The new bit value for the pixel
     */
    private void setPixelBit(int x, int y, int channel, int bit, boolean bitValue) {
        int pixel;
        int newColor;
        int newPixel;

        // Get the pixel value
        pixel = this.image.getRGB(x, y);

        // Set the bit value
        if (bitValue) {
            newPixel = pixel | 1 << (bit + (channel * 8));
        } else {
            newColor = 0xfffffffe;
            for (int i = 0; i < (bit + (channel * 8)); i++) {
                newColor = (newColor << 1) | 0x1;
            }
            newPixel = pixel & newColor;
        }

        // Set the pixel value back in image
        this.image.setRGB(x, y, newPixel);
    }
}
