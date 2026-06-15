/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.util.LabelUtil;

import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream to embed data into image
 */
public class LSBOutputStream extends OutputStream {
    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(LSBPlugin.NAMESPACE);

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
     * Current x co-ordinate
     */
    private int x = 0;

    /**
     * Current y co-ordinate
     */
    private int y = 0;

    /**
     * Current bit number to be read
     */
    private int currBit = 0;

    /**
     * Bit set to store three bits per pixel
     */
    private byte[] bitSet;

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
     * Default constructor
     *
     * @param image      Source image into which data will be embedded
     * @param dataLength Length of the data that would be written to the image
     * @param fileName   Name of the source data file
     * @param config     Configuration data to use while writing
     * @throws OpenStegoException Processing issues
     */
    public LSBOutputStream(PixelImage image, int dataLength, String fileName, OpenStegoConfig config) throws OpenStegoException {
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
        this.bitSet = new byte[3];
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

            if (this.currBit != 0) {
                this.currBit = 0;
                writeCurrentBitSet();
                nextPixel();
            }

            this.channelBitsUsed = channelBits;
            this.bitSet = new byte[3 * channelBits];
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
     * @throws IOException Write issues
     */
    @Override
    public void write(int data) throws IOException {
        for (int bit = 0; bit < 8; bit++) {
            this.bitSet[this.currBit] = (byte) ((data >> (7 - bit)) & 1);
            this.currBit++;
            if (this.currBit == this.bitSet.length) {
                this.currBit = 0;
                writeCurrentBitSet();
                nextPixel();
            }
        }
    }

    /**
     * Flushes the stream
     *
     * @throws IOException Write issues
     */
    @Override
    public void flush() throws IOException {
        writeCurrentBitSet();
    }

    /**
     * Closes the stream
     *
     * @throws IOException Write issues
     */
    @Override
    public void close() throws IOException {
        if (this.currBit != 0) {
            for (int i = this.currBit; i < this.bitSet.length; i++) {
                this.bitSet[i] = 0;
            }
            this.currBit = 0;
            writeCurrentBitSet();
            nextPixel();
        }
        super.close();
    }

    /**
     * Get the image containing the embedded data. Ideally, this should be called after the stream is closed.
     *
     * @return Image data
     * @throws OpenStegoException Processing issues
     */
    public PixelImage getImage() throws OpenStegoException {
        try {
            flush();
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
        return this.image;
    }

    /**
     * Method to write current bit set
     *
     * @throws IOException Write issues
     */
    private void writeCurrentBitSet() throws IOException {
        int pixel;
        int offset = 0;
        int mask;
        int maskPerByte;
        int bitOffset;

        if (this.y == this.imgHeight) {
            throw new IOException(labelUtil.getString("err.image.insufficientSize"));
        }

        maskPerByte = (1 << this.channelBitsUsed) - 1;
        mask = (maskPerByte << 16) + (maskPerByte << 8) + maskPerByte;
        pixel = this.image.getRGB(this.x, this.y) & (0xFFFFFFFF - mask);

        for (int bit = 0; bit < 3; bit++) {
            bitOffset = 0;
            for (int i = 0; i < this.channelBitsUsed; i++) {
                bitOffset = (bitOffset << 1) + this.bitSet[(bit * this.channelBitsUsed) + i];
            }
            offset = (offset << 8) + bitOffset;
        }
        this.image.setRGB(this.x, this.y, pixel + offset);
    }

    /**
     * Method to move on to next pixel
     */
    private void nextPixel() {
        this.x++;
        if (this.x == this.imgWidth) {
            this.x = 0;
            this.y++;
        }
    }
}
