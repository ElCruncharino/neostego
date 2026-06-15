/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the alpha (transparency) channel of an ARGB cover survives the embed/extract
 * round-trip unchanged for every data-hiding algorithm. Embedding only modifies the RGB channels,
 * so the hidden message must come back intact while the alpha plane is byte-identical to the cover.
 */
public class TransparencyTest {

    private static byte[] argbCover;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        argbCover = makeArgbPng(128, 96);
    }

    /** Builds a PNG with a non-trivial, per-pixel-varying alpha channel and random RGB content. */
    private static byte[] makeArgbPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(20260615L);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (x * 2 + y * 3) & 0xFF; // ranges across fully transparent -> opaque
                int rgb = rnd.nextInt() & 0x00FFFFFF;
                img.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static OpenStego newStego(String algorithm) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(algorithm);
        assertNotNull(plugin, algorithm + " plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        return new OpenStego(plugin, plugin.getConfig());
    }

    private void roundTripPreservesAlpha(String algorithm) throws Exception {
        byte[] msg = ("transparency round-trip for " + algorithm).getBytes(StandardCharsets.UTF_8);

        byte[] stego = newStego(algorithm).embedData(msg, "m.txt", argbCover, "cover.png", "stego.png");

        // Message comes back intact
        List<?> out = newStego(algorithm).extractData(stego, "stego.png");
        assertEquals("m.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1), algorithm + " must recover the message");

        // Alpha plane is byte-identical between cover and stego
        BufferedImage cover = ImageIO.read(new java.io.ByteArrayInputStream(argbCover));
        BufferedImage steg = ImageIO.read(new java.io.ByteArrayInputStream(stego));
        // ImageIO may hand back any alpha-capable representation (e.g. TYPE_4BYTE_ABGR); what matters
        // is that the stego image still carries an alpha channel.
        assertTrue(steg.getColorModel().hasAlpha(), "stego image must retain an alpha channel");
        for (int y = 0; y < cover.getHeight(); y++) {
            for (int x = 0; x < cover.getWidth(); x++) {
                int ca = (cover.getRGB(x, y) >>> 24);
                int sa = (steg.getRGB(x, y) >>> 24);
                assertEquals(ca, sa, "alpha at (" + x + "," + y + ") must be unchanged for " + algorithm);
            }
        }
    }

    @Test
    public void adaptivePreservesAlpha() throws Exception {
        roundTripPreservesAlpha("Adaptive");
    }

    @Test
    public void randomLsbMatchPreservesAlpha() throws Exception {
        roundTripPreservesAlpha("RandomLSBMatch");
    }

    @Test
    public void randomLsbPreservesAlpha() throws Exception {
        roundTripPreservesAlpha("RandomLSB");
    }
}
