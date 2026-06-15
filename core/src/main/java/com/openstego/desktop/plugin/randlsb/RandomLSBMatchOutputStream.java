/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
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
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * OutputStream to embed data into an image using LSB matching (&plusmn;1 embedding).
 * <p>
 * Unlike plain LSB replacement (which overwrites the least-significant bit and leaves the tell-tale
 * "pairs of values" artifact that RS / Sample-Pair / Chi-square steganalysis detects), this stream
 * sets the target LSB by adding or subtracting one from the channel value. The recovered LSBs are
 * identical to LSB replacement, so the data is read back with the normal Random-LSB extraction.
 * <p>
 * The pixel positions are chosen by a password-seeded PRNG that <em>must</em> stay in lock-step with
 * the reader, so the random choice of +1 vs -1 uses a separate, independent RNG.
 */
public class RandomLSBMatchOutputStream extends OutputStream {
    private final PixelImage image;
    private int channelBitsUsed;
    private final int dataLength;
    private final String fileName;
    private final int imgWidth;
    private final int imgHeight;
    private final OpenStegoConfig config;
    private final Set<String> bitWritten = new HashSet<>();

    /**
     * Password-seeded PRNG for choosing embedding positions (kept in sync with the reader)
     */
    private final Random rand;

    /**
     * Independent RNG for choosing the +1/-1 direction (must NOT perturb the position PRNG)
     */
    private final SecureRandom directionRand = new SecureRandom();

    /**
     * Default constructor
     *
     * @param image      Source image into which data will be embedded
     * @param dataLength Length of the data that would be written to the image
     * @param fileName   Name of the source data file
     * @param config     Configuration data to use while writing
     * @throws OpenStegoException Processing issues
     */
    public RandomLSBMatchOutputStream(PixelImage image, int dataLength, String fileName, OpenStegoConfig config) throws OpenStegoException {
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

        // Initialize position PRNG with seed generated from the password (same as the reader)
        this.rand = new Random(StringUtil.passwordHash(config.getPassword()));
        writeHeader();
    }

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
     * Sets the given bit of the pixel to the desired value. For the least-significant bit this uses
     * &plusmn;1 matching; higher bits (only reachable if more than one bit per channel is allowed) fall
     * back to direct replacement, since &plusmn;1 cannot target them.
     */
    private void setPixelBit(int x, int y, int channel, int bit, boolean bitValue) {
        int pixel = this.image.getRGB(x, y);
        int shift = bit + (channel * 8);
        boolean currentBit = ((pixel >> shift) & 0x1) == 0x1;
        if (currentBit == bitValue) {
            return; // Already the desired value, no change needed
        }

        if (bit == 0) {
            // LSB matching: flip the least-significant bit by +/-1 on the channel value
            int channelVal = (pixel >> (channel * 8)) & 0xFF;
            int delta;
            if (channelVal == 0) {
                delta = 1;
            } else if (channelVal == 255) {
                delta = -1;
            } else {
                delta = this.directionRand.nextBoolean() ? 1 : -1;
            }
            int newChannelVal = channelVal + delta;
            int mask = ~(0xFF << (channel * 8));
            int newPixel = (pixel & mask) | (newChannelVal << (channel * 8));
            this.image.setRGB(x, y, newPixel);
        } else {
            // Higher bit: direct replacement (toggle the specific bit)
            int newPixel = pixel ^ (1 << shift);
            this.image.setRGB(x, y, newPixel);
        }
    }
}
