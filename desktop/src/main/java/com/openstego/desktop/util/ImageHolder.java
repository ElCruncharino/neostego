/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) 2017 Samir Vaidya
 */
package com.openstego.desktop.util;

import java.awt.image.BufferedImage;
import javax.imageio.metadata.IIOMetadata;

/**
 * Class to hold image and its metadata
 */
public class ImageHolder {
    private BufferedImage image;
    private IIOMetadata metadata;
    private byte[] iccProfile;

    /**
     * Default constructor
     *
     * @param image    Image
     * @param metadata Metadata
     */
    public ImageHolder(BufferedImage image, IIOMetadata metadata) {
        this.image = image;
        this.metadata = metadata;
    }

    /**
     * Raw ICC colour profile bytes carried from the source image so that they can be re-embedded into the
     * output (see {@link ImageUtil#tagWithProfile}). Null when the source carried no embedded profile.
     *
     * @return ICC profile bytes, or null
     */
    public byte[] getIccProfile() {
        return this.iccProfile;
    }

    /**
     * Setter for the ICC colour profile bytes.
     *
     * @param iccProfile ICC profile bytes (may be null)
     */
    public void setIccProfile(byte[] iccProfile) {
        this.iccProfile = iccProfile;
    }

    /**
     * Getter method for image
     *
     * @return image
     */
    public BufferedImage getImage() {
        return this.image;
    }

    /**
     * Setter method for image
     *
     * @param image Value for image to be set
     */
    public void setImage(BufferedImage image) {
        this.image = image;
    }

    /**
     * Getter method for metadata
     *
     * @return metadata
     */
    public IIOMetadata getMetadata() {
        return this.metadata;
    }

    /**
     * Setter method for metadata
     *
     * @param metadata Value for metadata to be set
     */
    @SuppressWarnings("unused")
    public void setMetadata(IIOMetadata metadata) {
        this.metadata = metadata;
    }
}
