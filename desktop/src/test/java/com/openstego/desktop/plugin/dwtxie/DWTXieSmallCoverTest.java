/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtxie;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.PluginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression test: a cover too small for the signature's decomposition level must fail with a
 * clean OpenStegoException, not a NullPointerException from an unguarded DWT tree walk.
 */
public class DWTXieSmallCoverTest {

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    private static OpenStegoPlugin<?> plugin() throws Exception {
        // DWTXiePlugin is not listed in OpenStegoPlugins.internal (Android instantiates it
        // directly for watermarking), so it's constructed directly here too.
        OpenStegoPlugin<?> plugin = new DWTXiePlugin();
        plugin.resetConfig();
        plugin.getConfig().setPassword("test-key");
        return plugin;
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rand = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rand.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    public void smallCoverThrowsCleanException() throws Exception {
        OpenStegoPlugin<?> p = plugin();
        byte[] sig = p.generateSignature();
        byte[] small = png(64, 64);

        OpenStegoException embedEx = assertThrows(
                OpenStegoException.class, () -> p.embedData(sig, "key.sig", small, "cover.png", "stego.png"));
        assertEquals(DWTXiePlugin.NAMESPACE, embedEx.getNamespace());
        assertEquals(DWTXieErrors.ERR_FILE_TOO_SMALL, embedEx.getErrorCode());

        OpenStegoException extractEx =
                assertThrows(OpenStegoException.class, () -> p.extractData(small, "stego.png", sig));
        assertEquals(DWTXiePlugin.NAMESPACE, extractEx.getNamespace());
        assertEquals(DWTXieErrors.ERR_FILE_TOO_SMALL, extractEx.getErrorCode());
    }

    @Test
    public void nonSquareSmallCoverThrowsCleanException() throws Exception {
        OpenStegoPlugin<?> p = plugin();
        byte[] sig = p.generateSignature();
        byte[] small = png(100, 40);

        OpenStegoException ex = assertThrows(
                OpenStegoException.class, () -> p.embedData(sig, "key.sig", small, "cover.png", "stego.png"));
        assertEquals(DWTXieErrors.ERR_FILE_TOO_SMALL, ex.getErrorCode());
    }

    @Test
    public void largeEnoughCoverStillWorks() throws Exception {
        OpenStegoPlugin<?> p = plugin();
        byte[] sig = p.generateSignature();
        byte[] big = png(256, 256);

        byte[] stego = assertDoesNotThrow(() -> p.embedData(sig, "key.sig", big, "cover.png", "stego.png"));
        assertNotNull(stego);
        assertNotNull(assertDoesNotThrow(() -> p.extractData(stego, "stego.png", sig)));
    }
}
