/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trip regression test for the desktop GUI's multi-algorithm embed/extract contract.
 * <p>
 * The embed screen lets the user pick any data-hiding plugin, but the extract screen carries no
 * algorithm selector: {@code OpenStegoUI.extractWithAutoDetect} tries the plugins in turn until one
 * decodes the file. That only works because (a) every plugin round-trips its own output and (b) the
 * plugins reject each other's files cleanly, so trying them in sequence recovers the payload no
 * matter which one wrote it. This test pins both properties down for the whole suite, mirroring the
 * controller's try-each ordering (JPEG plugin first for {@code .jpg}, spatial plugins otherwise).
 */
public class MultiAlgorithmRoundTripTest {

    private static final String MSG_FILE_NAME = "secret.txt";
    private static final byte[] SECRET =
            "round-trip across the suite".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static byte[] cover;

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();
        try (InputStream is = MultiAlgorithmRoundTripTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "Cover fixture must be on the classpath");
            cover = CommonUtil.streamToBytes(is);
        }
    }

    @Test
    public void testAdaptiveRoundTripViaAutoDetect() throws Exception {
        assertRoundTrip("Adaptive", "stego.png");
    }

    @Test
    public void testRandomLSBRoundTripViaAutoDetect() throws Exception {
        assertRoundTrip("RandomLSB", "stego.png");
    }

    @Test
    public void testRandomLSBMatchRoundTripViaAutoDetect() throws Exception {
        assertRoundTrip("RandomLSBMatch", "stego.png");
    }

    @Test
    public void testJpegUniwardRoundTripViaAutoDetect() throws Exception {
        byte[] stego = embed("JpegUniward", "stego.jpg");
        assertTrue(
                stego.length > 2 && (stego[0] & 0xFF) == 0xFF && (stego[1] & 0xFF) == 0xD8,
                "SI-UNIWARD output must be a JPEG (starts with FF D8)");
        List<?> output = extractWithAutoDetect(stego, "stego.jpg", null);
        assertEquals(MSG_FILE_NAME, output.get(0));
        assertArrayEquals(SECRET, (byte[]) output.get(1));
    }

    /**
     * Embeds with the named plugin, then recovers the payload via the auto-detect chain and asserts
     * the file name and bytes survive unchanged.
     */
    private void assertRoundTrip(String pluginName, String stegoFileName) throws Exception {
        byte[] stego = embed(pluginName, stegoFileName);
        List<?> output = extractWithAutoDetect(stego, stegoFileName, null);
        assertEquals(MSG_FILE_NAME, output.get(0), pluginName + ": file name should be recovered");
        assertArrayEquals(SECRET, (byte[]) output.get(1), pluginName + ": payload bytes must match");
    }

    private byte[] embed(String pluginName, String stegoFileName) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(pluginName);
        assertNotNull(plugin, "Plugin '" + pluginName + "' must be available");
        plugin.resetConfig();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(false);
        return new OpenStego(plugin, config).embedData(SECRET, MSG_FILE_NAME, cover, "cover.png", stegoFileName);
    }

    /**
     * Mirrors {@code OpenStegoUI.extractWithAutoDetect}: try each data-hiding plugin until one
     * decodes the file, JPEG plugin first for {@code .jpg}/{@code .jpeg} inputs.
     */
    private List<?> extractWithAutoDetect(byte[] stegoData, String stegoFileName, char[] password)
            throws OpenStegoException {
        OpenStegoException last = null;
        for (OpenStegoPlugin<?> plugin : orderPluginsForExtract(stegoFileName)) {
            plugin.resetConfig();
            OpenStegoConfig config = plugin.getConfig();
            config.setPassword(password == null ? null : password.clone());
            try {
                return new OpenStego(plugin, config).extractData(stegoData, stegoFileName);
            } catch (OpenStegoException e) {
                last = e;
            } finally {
                config.clearPassword();
            }
        }
        if (last != null) {
            throw last;
        }
        return fail("No plugin could extract the file");
    }

    private List<OpenStegoPlugin<?>> orderPluginsForExtract(String stegoFileName) {
        String lower = stegoFileName.toLowerCase();
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        List<OpenStegoPlugin<?>> ordered = new ArrayList<>();
        List<OpenStegoPlugin<?>> deferred = new ArrayList<>();
        for (OpenStegoPlugin<?> plugin : PluginManager.getDataHidingPlugins()) {
            if (jpeg == "JpegUniward".equals(plugin.getName())) {
                ordered.add(plugin);
            } else {
                deferred.add(plugin);
            }
        }
        ordered.addAll(deferred);
        return ordered;
    }
}
