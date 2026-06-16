/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
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
 * Regression coverage for upstream issue #63 (WebP support). A WebP cover must be readable (via the
 * TwelveMonkeys ImageIO plugin) so data can be hidden in it; the lossless stego output is written as PNG.
 */
public class WebpCoverTest {

    private static byte[] webpCover;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = WebpCoverTest.class.getResourceAsStream("/webp/cover.webp")) {
            assertNotNull(is, "webp test cover must exist");
            webpCover = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(String algorithm) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(algorithm);
        assertNotNull(plugin, algorithm + " plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void webpIsAReadableFormat() throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("RandomLSB");
        assertTrue(plugin.getReadableFileExtensions().contains("webp"),
                "webp must be a readable cover format once the TwelveMonkeys plugin is on the classpath");
    }

    @Test
    public void hidesDataInWebpCover() throws Exception {
        byte[] msg = "hidden in webp".getBytes(StandardCharsets.UTF_8);

        // WebP cover in, lossless PNG stego out.
        byte[] stego = newStego("RandomLSB").embedData(msg, "m.txt", webpCover, "cover.webp", "stego.png");

        List<?> out = newStego("RandomLSB").extractData(stego, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1), "message hidden in a WebP cover must round-trip");
    }
}
