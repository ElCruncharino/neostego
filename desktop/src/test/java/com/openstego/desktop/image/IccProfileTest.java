/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for upstream issues #62 ("icc profile is removed after embedding watermark") and the
 * JPEG/transparency half of #58. An sRGB ICC profile embedded in a cover must survive the data-hiding and
 * watermarking round-trips, and watermarking an image with alpha out to JPEG must flatten rather than crash
 * with "image cannot be encoded ...".
 */
public class IccProfileTest {

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    /** A PNG carrying an explicit (embedded) sRGB ICC profile, so ImageIO decodes it with an ICC colour space. */
    private static byte[] pngWithIccProfile(int w, int h, boolean alpha) throws Exception {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage base = new BufferedImage(w, h, type);
        Random rnd = new Random(7L);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = alpha ? ((x * 2 + y) & 0xFF) : 0xFF;
                base.setRGB(x, y, (a << 24) | (rnd.nextInt() & 0x00FFFFFF));
            }
        }
        ICC_ColorSpace cs = (ICC_ColorSpace) ColorSpace.getInstance(ColorSpace.CS_sRGB);
        int bits = alpha ? 32 : 24;
        int aMask = alpha ? 0xFF000000 : 0;
        DirectColorModel dcm = new DirectColorModel(cs, bits, 0xFF0000, 0xFF00, 0xFF, aMask, false, DataBuffer.TYPE_INT);
        BufferedImage tagged = new BufferedImage(dcm, base.getRaster(), false, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(tagged, "png", out);
        byte[] bytes = out.toByteArray();
        // Sanity: the cover really does decode with an embedded ICC profile.
        assertNotNull(profileOf(bytes), "test cover must carry an embedded ICC profile");
        return bytes;
    }

    private static byte[] profileOf(byte[] imageBytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        ColorSpace cs = img.getColorModel().getColorSpace();
        return (cs instanceof ICC_ColorSpace) ? ((ICC_ColorSpace) cs).getProfile().getData() : null;
    }

    private static OpenStego newStego(String algorithm) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(algorithm);
        assertNotNull(plugin, algorithm + " plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        return new OpenStego(plugin, plugin.getConfig());
    }

    private static OpenStego newWatermarkStego() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("DWTSVD");
        assertNotNull(plugin, "DWTSVD plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setPassword("icc-watermark-key");
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void dataHidingKeepsIccProfileAndMessage() throws Exception {
        byte[] cover = pngWithIccProfile(96, 72, false);
        byte[] msg = "icc data-hiding".getBytes(StandardCharsets.UTF_8);

        byte[] stego = newStego("RandomLSB").embedData(msg, "m.txt", cover, "cover.png", "stego.png");

        assertNotNull(profileOf(stego), "stego output must still carry the cover's ICC profile");
        List<?> out = newStego("RandomLSB").extractData(stego, "stego.png");
        org.junit.jupiter.api.Assertions.assertArrayEquals(msg, (byte[]) out.get(1), "message must round-trip intact");
    }

    @Test
    public void watermarkKeepsIccProfilePng() throws Exception {
        byte[] cover = pngWithIccProfile(512, 512, false);
        OpenStego stego = newWatermarkStego();
        byte[] sig = stego.generateSignature();

        byte[] wm = stego.embedMark(sig, "key.sig", cover, "cover.png", "stego.png");

        assertNotNull(profileOf(wm), "watermarked PNG must keep the ICC profile (issue #62)");
        assertTrue(newWatermarkStego().checkMark(wm, "stego.png", sig) > 0.5, "watermark must still verify");
    }

    @Test
    public void watermarkKeepsIccProfileJpeg() throws Exception {
        byte[] cover = pngWithIccProfile(512, 512, false);
        OpenStego stego = newWatermarkStego();
        byte[] sig = stego.generateSignature();

        byte[] wm = stego.embedMark(sig, "key.sig", cover, "cover.png", "stego.jpg");

        assertNotNull(profileOf(wm), "watermarked JPEG must keep the ICC profile via APP2 (issue #62)");
    }

    @Test
    public void watermarkAlphaCoverToJpegDoesNotCrash() throws Exception {
        byte[] cover = pngWithIccProfile(512, 512, true);
        OpenStego stego = newWatermarkStego();
        byte[] sig = stego.generateSignature();

        // Historically threw "image cannot be encoded with compression type BI_RGB" (issue #58). The alpha
        // must be flattened so a valid JPEG is produced.
        byte[] wm = stego.embedMark(sig, "key.sig", cover, "cover.png", "stego.jpg");

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(wm));
        assertNotNull(out, "must produce a readable JPEG");
        assertEquals(512, out.getWidth());
    }
}
