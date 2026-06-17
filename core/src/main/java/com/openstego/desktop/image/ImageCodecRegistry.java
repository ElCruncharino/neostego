/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Locates the active {@link ImageCodec} for the current platform.
 * <p>
 * By default the implementation is discovered via {@link ServiceLoader} (each platform module ships a
 * {@code META-INF/services/com.openstego.desktop.image.ImageCodec} entry). It can also be set
 * explicitly via {@link #set(ImageCodec)} (e.g. in tests or non-standard environments).
 */
public final class ImageCodecRegistry {
    private static ImageCodec codec;

    private ImageCodecRegistry() {}

    /**
     * Returns the active image codec, discovering one via {@link ServiceLoader} on first use.
     *
     * @return Active image codec
     */
    public static synchronized ImageCodec get() {
        if (codec == null) {
            Iterator<ImageCodec> it = ServiceLoader.load(ImageCodec.class).iterator();
            if (it.hasNext()) {
                codec = it.next();
            } else {
                throw new IllegalStateException(
                        "No ImageCodec implementation found on the classpath (expected a platform module to provide one)");
            }
        }
        return codec;
    }

    /**
     * Sets the active image codec explicitly, overriding service discovery.
     *
     * @param imageCodec Codec to use
     */
    public static synchronized void set(ImageCodec imageCodec) {
        codec = imageCodec;
    }
}
