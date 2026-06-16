/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.compat;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and failure-mode tests for splitting one payload across several cover images
 * (upstream issue #67), exercising {@link MultiCoverPayloadSplitter} directly. The covers are
 * generated at sizes too small to hold the whole payload individually, so a correct split must use
 * all of them and reassemble them in order.
 */
public class MultiCoverPayloadSplitTest {

    private static final String MSG_FILE_NAME = "secret.bin";

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();
    }

    @SuppressWarnings("unchecked")
    private static DHImagePluginTemplate<OpenStegoConfig> lsbPlugin() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("RandomLSB");
        assertTrue(plugin instanceof DHImagePluginTemplate, "RandomLSB must be an image data-hiding plugin");
        plugin.resetConfig();
        return (DHImagePluginTemplate<OpenStegoConfig>) plugin;
    }

    /** Builds a valid PNG cover with roughly the given number of pixels. */
    private static byte[] cover(int pixels) throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().createRandomImage(pixels);
        return ImageCodecRegistry.get().encode(image, "cover.png");
    }

    private static List<byte[]> covers(int count, int pixels) throws OpenStegoException {
        List<byte[]> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(cover(pixels));
        }
        return list;
    }

    private static List<String> names(int count, String base) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(base + i + ".png");
        }
        return list;
    }

    private static byte[] randomPayload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data); // incompressible, so chunk sizing is predictable
        return data;
    }

    @Test
    public void testRoundTripAcrossThreeCovers() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(6000, 1);
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config, plugin);
        assertEquals(3, stego.size(), "one stego image per cover");

        List<?> out = MultiCoverPayloadSplitter.extractSplit(stego, names(3, "stego"), plugin.getConfig(), plugin);
        assertEquals(MSG_FILE_NAME, out.get(0));
        assertArrayEquals(payload, (byte[]) out.get(1));
    }

    @Test
    public void testExtractPartsOutOfOrder() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(6000, 2);
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config, plugin);

        List<byte[]> shuffled = new ArrayList<>(Arrays.asList(stego.get(2), stego.get(0), stego.get(1)));
        List<?> out = MultiCoverPayloadSplitter.extractSplit(shuffled, names(3, "stego"), plugin.getConfig(), plugin);
        assertArrayEquals(payload, (byte[]) out.get(1), "parts must be reassembled by index, not input order");
    }

    @Test
    public void testRoundTripWithCompressionAndEncryption() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(true);
        config.setEncryptionAlgorithm("AES256");
        config.setPassword("hunter2".toCharArray());

        byte[] payload = randomPayload(5000, 3);
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config, plugin);

        plugin.resetConfig();
        OpenStegoConfig extractConfig = plugin.getConfig();
        extractConfig.setPassword("hunter2".toCharArray());
        List<?> out = MultiCoverPayloadSplitter.extractSplit(stego, names(3, "stego"), extractConfig, plugin);
        assertEquals(MSG_FILE_NAME, out.get(0));
        assertArrayEquals(payload, (byte[]) out.get(1));
    }

    @Test
    public void testRoundTripCompressionOnly() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(false);

        // Highly compressible payload so the compressed blob is comfortably small.
        byte[] payload = new byte[20000];
        Arrays.fill(payload, (byte) 'A');
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(2, 8000), names(2, "cover"), names(2, "stego"), config, plugin);

        List<?> out = MultiCoverPayloadSplitter.extractSplit(stego, names(2, "stego"), plugin.getConfig(), plugin);
        assertArrayEquals(payload, (byte[]) out.get(1));
    }

    @Test
    public void testMissingPartFails() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(6000, 4);
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config, plugin);

        List<byte[]> partial = new ArrayList<>(Arrays.asList(stego.get(0), stego.get(1)));
        OpenStegoException ex = assertThrows(OpenStegoException.class,
                () -> MultiCoverPayloadSplitter.extractSplit(partial, names(2, "stego"), plugin.getConfig(), plugin));
        assertEquals(OpenStegoErrors.SPLIT_MANIFEST_INCOMPLETE, ex.getErrorCode());
    }

    @Test
    public void testDuplicatePartFails() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(2000, 5);
        List<byte[]> stego = MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                covers(2, 4000), names(2, "cover"), names(2, "stego"), config, plugin);

        List<byte[]> dup = new ArrayList<>(Arrays.asList(stego.get(0), stego.get(0)));
        OpenStegoException ex = assertThrows(OpenStegoException.class,
                () -> MultiCoverPayloadSplitter.extractSplit(dup, names(2, "stego"), plugin.getConfig(), plugin));
        assertEquals(OpenStegoErrors.SPLIT_MANIFEST_CORRUPT, ex.getErrorCode());
    }

    @Test
    public void testForeignPartFails() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        List<byte[]> stegoA = MultiCoverPayloadSplitter.embedSplit(randomPayload(6000, 6), MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config, plugin);
        plugin.resetConfig();
        OpenStegoConfig config2 = plugin.getConfig();
        config2.setUseCompression(false);
        config2.setUseEncryption(false);
        List<byte[]> stegoB = MultiCoverPayloadSplitter.embedSplit(randomPayload(6000, 7), MSG_FILE_NAME,
                covers(3, 8000), names(3, "cover"), names(3, "stego"), config2, plugin);

        // Mix parts from two different splits.
        List<byte[]> mixed = new ArrayList<>(Arrays.asList(stegoA.get(0), stegoA.get(1), stegoB.get(2)));
        OpenStegoException ex = assertThrows(OpenStegoException.class,
                () -> MultiCoverPayloadSplitter.extractSplit(mixed, names(3, "stego"), plugin.getConfig(), plugin));
        assertEquals(OpenStegoErrors.SPLIT_MANIFEST_MISMATCH, ex.getErrorCode());
    }

    @Test
    public void testInsufficientCapacityFails() throws Exception {
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(false);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(4000, 8);
        OpenStegoException ex = assertThrows(OpenStegoException.class,
                () -> MultiCoverPayloadSplitter.embedSplit(payload, MSG_FILE_NAME,
                        covers(2, 800), names(2, "cover"), names(2, "stego"), config, plugin));
        assertEquals(OpenStegoErrors.SPLIT_INSUFFICIENT_CAPACITY, ex.getErrorCode());
    }

    @Test
    public void testSingleCoverPathStillWorks() throws Exception {
        // Control: a normal single-cover embed/extract is unaffected by the split feature.
        DHImagePluginTemplate<OpenStegoConfig> plugin = lsbPlugin();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(false);

        byte[] payload = randomPayload(500, 9);
        byte[] stego = new OpenStego(plugin, config).embedData(payload, MSG_FILE_NAME, cover(8000), "cover.png", "stego.png");

        plugin.resetConfig();
        List<?> out = new OpenStego(plugin, plugin.getConfig()).extractData(stego, "stego.png");
        assertEquals(MSG_FILE_NAME, out.get(0));
        assertArrayEquals(payload, (byte[]) out.get(1));
    }
}
