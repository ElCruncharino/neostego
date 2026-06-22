/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the SI-UNIWARD JPEG plugin: PNG precover in &rarr; JPEG out &rarr; byte-exact
 * message recovery, with and without encryption/compression, across payload sizes and qualities;
 * filename recovery; wrong-password rejection; capacity guard; and a check that the stego output is a
 * genuine, decodable JPEG of the right dimensions.
 */
public class JpegUniwardPluginTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = JpegUniwardPluginTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(boolean compress, boolean encrypt, String password, int quality)
            throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        assertNotNull(plugin, "JpegUniward plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(compress);
        plugin.getConfig().setUseEncryption(encrypt);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        ((JpegUniwardConfig) plugin.getConfig()).setQuality(quality);
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void plainRoundTrip() throws Exception {
        byte[] msg = "SI-UNIWARD JPEG round-trip — raises the bar.".getBytes(StandardCharsets.UTF_8);
        byte[] stego =
                newStego(false, false, null, 90).embedData(msg, "note.txt", coverBytes, "cover.png", "stego.jpg");
        List<?> out = newStego(false, false, null, 90).extractData(stego, "stego.jpg");
        assertEquals("note.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void outputIsADecodableJpeg() throws Exception {
        byte[] msg = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego(false, false, null, 90).embedData(msg, "m.txt", coverBytes, "cover.png", "stego.jpg");
        // SOI marker
        assertEquals((byte) 0xFF, stego[0]);
        assertEquals((byte) 0xD8, stego[1]);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(stego));
        assertNotNull(img, "stego output must be a JPEG decodable by ImageIO");
        BufferedImage cover = ImageIO.read(new ByteArrayInputStream(coverBytes));
        assertEquals(cover.getWidth(), img.getWidth());
        assertEquals(cover.getHeight(), img.getHeight());
    }

    @Test
    public void roundTripAcrossPayloads() throws Exception {
        int[] sizes = {1, 64, 1000, 6000};
        for (int n : sizes) {
            byte[] msg = new byte[n];
            new Random(n * 7L + 1).nextBytes(msg);
            byte[] stego =
                    newStego(false, false, "pw", 85).embedData(msg, "p.bin", coverBytes, "cover.png", "stego.jpg");
            List<?> out = newStego(false, false, "pw", 85).extractData(stego, "stego.jpg");
            assertArrayEquals(msg, (byte[]) out.get(1), "payload " + n + " bytes must round-trip");
        }
    }

    @Test
    public void roundTripAcrossQualities() throws Exception {
        byte[] msg = new byte[800];
        new Random(99).nextBytes(msg);
        for (int q : new int[] {70, 80, 95}) {
            byte[] stego =
                    newStego(false, false, null, q).embedData(msg, "q.bin", coverBytes, "cover.png", "stego.jpg");
            List<?> out = newStego(false, false, null, q).extractData(stego, "stego.jpg");
            assertArrayEquals(msg, (byte[]) out.get(1), "quality " + q + " must round-trip");
        }
    }

    @Test
    public void encryptedCompressedRoundTrip() throws Exception {
        byte[] msg = new byte[4096];
        new Random(13).nextBytes(msg);
        String pw = "correct horse battery staple";
        byte[] stego = newStego(true, true, pw, 90).embedData(msg, "secret.bin", coverBytes, "cover.png", "stego.jpg");
        List<?> out = newStego(true, true, pw, 90).extractData(stego, "stego.jpg");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void wrongPasswordDoesNotRecoverData() throws Exception {
        byte[] msg = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] stego =
                newStego(false, true, "rightpass", 90).embedData(msg, "s.txt", coverBytes, "cover.png", "stego.jpg");
        boolean recovered;
        try {
            List<?> out = newStego(false, true, "wrongpass", 90).extractData(stego, "stego.jpg");
            recovered = java.util.Arrays.equals(msg, (byte[]) out.get(1));
        } catch (OpenStegoException ex) {
            recovered = false;
        }
        assertTrue(!recovered, "wrong password must not recover the message");
    }

    @Test
    public void oversizedMessageIsRejected() throws Exception {
        byte[] msg = new byte[5_000_000];
        OpenStego stego = newStego(false, false, null, 90);
        assertThrows(
                OpenStegoException.class, () -> stego.embedData(msg, "big.bin", coverBytes, "cover.png", "stego.jpg"));
    }
}
