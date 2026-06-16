/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for upstream issue #69 ("watermarking not robust"). The reporter cropped a slightly
 * smaller image and re-saved, and the watermark dropped to 0%. The extract-time grid-resynchronisation makes
 * the DWT-SVD watermark survive a small <em>pure</em> crop (no rescale) - the common "trim the edges" edit.
 */
public class DWTSVDCropTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = DWTSVDCropTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("DWTSVD");
        plugin.resetConfig();
        plugin.getConfig().setPassword("crop-key");
        return new OpenStego(plugin, plugin.getConfig());
    }

    /** Removes {@code inset} pixels from each edge (a pure crop, no rescale) and re-encodes as PNG. */
    private static byte[] cropEdges(byte[] png, int inset) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage cropped = img.getSubimage(inset, inset, img.getWidth() - 2 * inset, img.getHeight() - 2 * inset);
        BufferedImage copy = new BufferedImage(cropped.getWidth(), cropped.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(cropped, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(copy, "png", out);
        return out.toByteArray();
    }

    @Test
    public void survivesSmallEdgeCrop() throws Exception {
        OpenStego stego = newStego();
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        // A clean (uncropped) check is still ~1.0 - the fast path is unchanged.
        assertTrue(newStego().checkMark(wm, "stego.png", sig) > 0.95, "uncropped watermark must verify ~1.0");

        // Small pure crops of a few/several pixels per edge must still verify above the high threshold (0.5).
        for (int inset : new int[]{6, 8, 12, 16}) {
            byte[] cropped = cropEdges(wm, inset);
            double corr = newStego().checkMark(cropped, "stego.png", sig);
            assertTrue(corr > 0.5, "watermark must survive a " + inset + "px edge crop, correlation=" + corr);
        }
    }

    @Test
    public void croppedImageDoesNotFalselyMatchWrongSignature() throws Exception {
        OpenStego stego = newStego();
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        // A different signature (different password) must read as absent even on a cropped image: the alignment
        // search must not fabricate a watermark for a signature that was never embedded.
        OpenStegoPlugin<?> other = PluginManager.getPluginByName("DWTSVD");
        other.resetConfig();
        other.getConfig().setPassword("a-totally-different-key");
        byte[] otherSig = new OpenStego(other, other.getConfig()).generateSignature();

        byte[] cropped = cropEdges(wm, 12);
        double corr = newStego().checkMark(cropped, "stego.png", otherSig);
        assertTrue(corr < 0.5, "a different signature must stay absent on a cropped image, got " + corr);
    }
}
