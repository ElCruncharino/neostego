/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.util.PluginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for upstream issue #67. A payload that does not fit the cover's capacity must fail
 * fast with a clear OpenStegoException rather than silently truncating or (for large inputs) dying with an
 * OutOfMemoryError. This pins the "data too large for cover" contract for the small, in-memory case; the
 * genuinely huge (multi-GB) case is the same capacity limit, just reported via err.memory.full once the heap
 * is exhausted.
 */
public class CapacityLimitTest {

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    private static byte[] smallCover() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static OpenStego newStego() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("RandomLSB");
        assertNotNull(plugin);
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void oversizedPayloadFailsClearly() throws Exception {
        byte[] cover = smallCover();
        // 32x32 RGB holds ~ (32*32*3)/8 = 384 bytes minus the header; 64 KB cannot possibly fit.
        byte[] huge = new byte[64 * 1024];

        OpenStegoException ex = assertThrows(
                OpenStegoException.class, () -> newStego().embedData(huge, "big.bin", cover, "cover.png", "stego.png"));
        // Must be the capacity error, not some opaque failure.
        assertTrue(
                ex.getMessage() != null && ex.getMessage().toLowerCase().contains("insufficient")
                        || ex.getMessage().toLowerCase().contains("size")
                        || ex.getMessage().toLowerCase().contains("large"),
                "expected a capacity-related message, got: " + ex.getMessage());
    }
}
