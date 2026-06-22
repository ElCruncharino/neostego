/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.randlsb;

import static org.junit.jupiter.api.Assertions.*;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.awt.BufferedImagePixelImage;
import com.openstego.desktop.plugin.lsb.LSBConfig;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.plugin.lsb.LSBErrors;
import com.openstego.desktop.plugin.lsb.LSBPlugin;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test class for {@link com.openstego.desktop.plugin.randlsb.RandomLSBOutputStream}
 */
public class RandomLSBOutputStreamTest {

    @BeforeEach
    public void setup() {
        RandomLSBPlugin plugin = new RandomLSBPlugin();
        assertNotNull(plugin);
    }

    @Test
    public void testBestCase() throws Exception {
        PixelImage image = new BufferedImagePixelImage(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
        LSBConfig config = new LSBConfig();
        String msg = "abcde";

        try (RandomLSBOutputStream os = new RandomLSBOutputStream(image, 5, "test.txt", config)) {
            assertNotNull(os);
            // Write simple message
            os.write(msg.getBytes(StandardCharsets.UTF_8));
            os.flush();

            image = os.getImage();
        }

        // Extract data back using RandomLSBInputStream and compare with original
        try (RandomLSBInputStream is = new RandomLSBInputStream(image, new LSBConfig())) {
            LSBDataHeader header = is.getDataHeader();
            assertEquals(1, header.getChannelBitsUsed());
            assertEquals(5, header.getDataLength());
            assertEquals("test.txt", header.getFileName());
            byte[] extMsg = new byte[5];
            int n = is.read(extMsg);
            assertEquals(msg.length(), n);
            assertEquals(msg, new String(extMsg, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testNullImage() throws Exception {
        try (RandomLSBOutputStream ignored = new RandomLSBOutputStream(null, 100, "test.txt", null)) {
            fail("Did not throw OpenStegoException");
        } catch (OpenStegoException e) {
            assertEquals(LSBPlugin.NAMESPACE, e.getNamespace());
            assertEquals(LSBErrors.NULL_IMAGE_ARGUMENT, e.getErrorCode());
        }
    }

    @Test
    public void testEmbedIntoNonRgbBackedImage() throws Exception {
        // A non-INT_RGB backing image must still round-trip (the low 24 bits carry the data)
        PixelImage image = new BufferedImagePixelImage(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB));
        LSBConfig config = new LSBConfig();
        String msg = "hello";

        try (RandomLSBOutputStream os = new RandomLSBOutputStream(image, msg.length(), "test.txt", config)) {
            os.write(msg.getBytes(StandardCharsets.UTF_8));
            os.flush();
            image = os.getImage();
        }

        try (RandomLSBInputStream is = new RandomLSBInputStream(image, new LSBConfig())) {
            byte[] extMsg = new byte[msg.length()];
            is.read(extMsg);
            assertEquals(msg, new String(extMsg, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testImageCapacity() throws Exception {
        PixelImage image = new BufferedImagePixelImage(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
        LSBConfig config = new LSBConfig();
        // With 100x100 image and 3 bits per channel used for data, approximately 90000/8 bytes can be embedded
        // Check that 11k bytes are ok, but 12k bytes fails
        try (RandomLSBOutputStream os = new RandomLSBOutputStream(image, 11000, "test.txt", config)) {
            assertNotNull(os);
        }
        try (RandomLSBOutputStream ignored = new RandomLSBOutputStream(image, 12000, "test.txt", config)) {
            fail("Did not throw OpenStegoException");
        } catch (OpenStegoException oe) {
            assertEquals(LSBPlugin.NAMESPACE, oe.getNamespace());
            assertEquals(LSBErrors.IMAGE_SIZE_INSUFFICIENT, oe.getErrorCode());
        }
    }
}
