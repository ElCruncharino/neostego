/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.randlsb;

import com.openstego.desktop.OpenStego;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Random LSB matching plugin: round-trip correctness, cross-compatibility with plain
 * Random LSB extraction (matching produces identical LSBs), and the &plusmn;1 embedding invariant.
 */
public class RandomLSBMatchTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = RandomLSBMatchTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(String pluginName) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(pluginName);
        assertNotNull(plugin, "Plugin must be available: " + pluginName);
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void testMatchRoundTrip() throws Exception {
        byte[] msg = "LSB matching round-trip message".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego("RandomLSBMatch").embedData(msg, "m.txt", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego("RandomLSBMatch").extractData(stego, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void testPlainRandomLsbCanExtractMatchedData() throws Exception {
        // Matching yields the same LSBs as replacement, so the plain RandomLSB plugin must read it back
        byte[] msg = "cross-compatible extraction".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego("RandomLSBMatch").embedData(msg, "m.txt", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego("RandomLSB").extractData(stego, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void testEmbeddingChangesChannelsByAtMostOne() throws Exception {
        byte[] msg = new byte[2048];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) (i * 31 + 7);
        }
        byte[] stego = newStego("RandomLSBMatch").embedData(msg, "m.bin", coverBytes, "cover.png", "stego.png");

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
}
