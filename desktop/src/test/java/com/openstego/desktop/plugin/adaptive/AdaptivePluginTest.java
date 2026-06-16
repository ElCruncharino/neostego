/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the content-adaptive (HILL+STC) plugin: round-trip with and without
 * encryption/compression, filename recovery, the &plusmn;1 (LSB matching) embedding invariant, and
 * the capacity guard.
 */
public class AdaptivePluginTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = AdaptivePluginTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(boolean compress, boolean encrypt, String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("Adaptive");
        assertNotNull(plugin, "Adaptive plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(compress);
        plugin.getConfig().setUseEncryption(encrypt);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void plainRoundTrip() throws Exception {
        byte[] msg = "content-adaptive HILL+STC round-trip".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego(false, false, null).embedData(msg, "note.txt", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego(false, false, null).extractData(stego, "stego.png");
        assertEquals("note.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void encryptedCompressedRoundTrip() throws Exception {
        byte[] msg = new byte[4096];
        new Random(13).nextBytes(msg);
        String pw = "correct horse battery staple";
        byte[] stego = newStego(true, true, pw).embedData(msg, "secret.bin", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego(true, true, pw).extractData(stego, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void wrongPasswordDoesNotRecoverData() throws Exception {
        byte[] msg = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego(false, true, "rightpass").embedData(msg, "s.txt", coverBytes, "cover.png", "stego.png");
        // A different password permutes/decrypts differently, so extraction must not yield the message
        boolean recovered;
        try {
            List<?> out = newStego(false, true, "wrongpass").extractData(stego, "stego.png");
            recovered = java.util.Arrays.equals(msg, (byte[]) out.get(1));
        } catch (OpenStegoException ex) {
            recovered = false; // failing to extract is an acceptable outcome
        }
        assertTrue(!recovered, "wrong password must not recover the message");
    }

    @Test
    public void embeddingChangesChannelsByAtMostOne() throws Exception {
        byte[] msg = new byte[3000];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) (i * 31 + 7);
        }
        byte[] stego = newStego(false, false, null).embedData(msg, "m.bin", coverBytes, "cover.png", "stego.png");

        ImageHolder coverImg = ImageUtil.byteArrayToImage(coverBytes, "cover.png");
        ImageHolder stegoImg = ImageUtil.byteArrayToImage(stego, "stego.png");
        int w = coverImg.getImage().getWidth();
        int h = coverImg.getImage().getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int cp = coverImg.getImage().getRGB(x, y);
                int sp = stegoImg.getImage().getRGB(x, y);
                for (int ch = 0; ch < 3; ch++) {
                    int cv = (cp >> (ch * 8)) & 0xFF;
                    int sv = (sp >> (ch * 8)) & 0xFF;
                    assertTrue(Math.abs(cv - sv) <= 1,
                            "Each channel must change by at most 1 (matching); got " + cv + " -> " + sv);
                }
            }
        }
    }

    @Test
    public void oversizedMessageIsRejected() throws Exception {
        // 400x300x3 = 360000 samples -> ~44 KB max; ask for far more
        byte[] msg = new byte[200_000];
        OpenStego stego = newStego(false, false, null);
        assertThrows(OpenStegoException.class,
                () -> stego.embedData(msg, "big.bin", coverBytes, "cover.png", "stego.png"));
    }
}
