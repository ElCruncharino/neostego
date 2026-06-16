/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.compat;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.plugin.adaptive.AdaptiveConfig;
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the tiled (banded) embedding of the content-adaptive and SI-UNIWARD plugins handles a
 * full-resolution cover without the O(image) working-set blow-up that used to crash large embeds
 * (an ~13&nbsp;MP cover plus a six-figure message would exhaust the heap before STC even ran).
 * <p>
 * The cover is a ~13.5&nbsp;MP textured image and the payload is 150&nbsp;KB &mdash; both larger than
 * the field repro. Each algorithm (Adaptive plain STC, Adaptive CMD, and SI-UNIWARD) embeds and then
 * extracts byte-for-byte. Run under a constrained heap (the dedicated {@code :desktop:tilingTest}
 * Gradle task caps {@code -Xmx}), so any regression that reintroduces a per-image array instead of a
 * per-band one fails loudly with {@code OutOfMemoryError} rather than passing on a roomy box. The
 * banding is the <em>only</em> format for these two plugins, so correctness here is the whole contract.
 */
public class LargeCoverTilingTest {

    // ~13.5 MP, comfortably past the ~13 MP field repro; 16-aligned so JPEG MCUs tile evenly.
    private static final int COVER_W = 4480;
    private static final int COVER_H = 3008;
    private static final int MSG_LEN = 150_000;
    private static final String MSG_FILE_NAME = "payload.bin";

    private static byte[] cover;
    private static byte[] msg;

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();

        // Textured (high-frequency) content keeps embedding costs low, so the whole grid is usable
        // capacity; deterministic so failures reproduce.
        BufferedImage img = new BufferedImage(COVER_W, COVER_H, BufferedImage.TYPE_INT_RGB);
        Random rand = new Random(20260615L);
        for (int y = 0; y < COVER_H; y++) {
            for (int x = 0; x < COVER_W; x++) {
                img.setRGB(x, y, rand.nextInt() & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        cover = bos.toByteArray();

        msg = new byte[MSG_LEN];
        new Random(424242L).nextBytes(msg);
    }

    @Test
    public void adaptivePlainStcRoundTrip() throws Exception {
        assertRoundTrip(adaptive(false), "stego.png");
    }

    @Test
    public void adaptiveCmdRoundTrip() throws Exception {
        assertRoundTrip(adaptive(true), "stego.png");
    }

    @Test
    public void siUniwardRoundTrip() throws Exception {
        assertRoundTrip(jpegUniward(), "stego.jpg");
    }

    private void assertRoundTrip(OpenStego stego, String stegoFileName) throws Exception {
        byte[] out = stego.embedData(msg, MSG_FILE_NAME, cover, "cover.png", stegoFileName);
        List<?> extracted = stego.extractData(out, stegoFileName);
        assertArrayEquals(msg, (byte[]) extracted.get(1),
                stegoFileName + ": 150 KB payload must round-trip byte-for-byte from a 13.5 MP cover");
    }

    private static OpenStego adaptive(boolean cmd) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("Adaptive");
        assertNotNull(plugin, "Adaptive plugin must be registered");
        plugin.resetConfig();
        AdaptiveConfig cfg = (AdaptiveConfig) plugin.getConfig();
        cfg.setUseCompression(false);
        cfg.setUseEncryption(false);
        cfg.setCmd(cmd);
        return new OpenStego(plugin, cfg);
    }

    private static OpenStego jpegUniward() throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        assertNotNull(plugin, "JpegUniward plugin must be registered");
        plugin.resetConfig();
        JpegUniwardConfig cfg = (JpegUniwardConfig) plugin.getConfig();
        cfg.setUseCompression(false);
        cfg.setUseEncryption(false);
        cfg.setQuality(90);
        return new OpenStego(plugin, cfg);
    }
}
