/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and disambiguation tests for the two JPEG-cover algorithms that embed into an
 * <em>already-compressed</em> JPEG: plain J-UNIWARD (SI-UNIWARD's {@code plainMode}) and F5. Both
 * differ from flagship SI-UNIWARD, which takes an uncompressed PNG/BMP precover. The fixtures here
 * build a JPEG cover on the fly from the shared PNG, then assert each algorithm round-trips its own
 * output and that the auto-detect chain disambiguates the two JPEG producers cleanly.
 */
public class JpegCoverAlgorithmsTest {

    private static final String MSG_FILE_NAME = "secret.txt";
    private static final byte[] SECRET = "round-trip on a JPEG cover".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static byte[] jpegCover;

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();
        byte[] png;
        try (InputStream is = JpegCoverAlgorithmsTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "Cover fixture must be on the classpath");
            png = CommonUtil.streamToBytes(is);
        }
        // Compress the PNG to a baseline JPEG once; this is the cover both JPEG-cover algorithms ingest.
        PixelImage precover = ImageCodecRegistry.get().decode(png, "cover.png");
        jpegCover = JpegCodec.encode(JpegCodec.fromPrecover(precover, 90));
        assertTrue(isJpeg(jpegCover), "Generated cover must be a JPEG");
    }

    @Test
    public void testF5RoundTripViaAutoDetect() throws Exception {
        byte[] stego = embedF5();
        assertTrue(isJpeg(stego), "F5 output must be a JPEG (starts with FF D8)");
        List<?> output = extractWithAutoDetect(stego, "stego.jpg");
        assertEquals(MSG_FILE_NAME, output.get(0), "F5: file name should be recovered");
        assertArrayEquals(SECRET, (byte[]) output.get(1), "F5: payload bytes must match");
    }

    @Test
    public void testPlainUniwardRoundTripViaAutoDetect() throws Exception {
        byte[] stego = embedPlainUniward();
        assertTrue(isJpeg(stego), "Plain J-UNIWARD output must be a JPEG");
        List<?> output = extractWithAutoDetect(stego, "stego.jpg");
        assertEquals(MSG_FILE_NAME, output.get(0), "Plain J-UNIWARD: file name should be recovered");
        assertArrayEquals(SECRET, (byte[]) output.get(1), "Plain J-UNIWARD: payload bytes must match");
    }

    /** Plain-mode flips the readable extensions to JPEG and back. */
    @Test
    public void testPlainModeSwitchesReadableExtensions() throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        plugin.resetConfig();
        ((JpegUniwardConfig) plugin.getConfig()).setPlainMode(true);
        assertTrue(plugin.getReadableFileExtensions().contains("jpg"), "plain mode reads JPEG");
        assertTrue(!plugin.getReadableFileExtensions().contains("png"), "plain mode does not read PNG");
        plugin.resetConfig();
        assertTrue(plugin.getReadableFileExtensions().contains("png"), "SI mode reads PNG precover");
    }

    /**
     * The two JPEG producers must reject each other's output cleanly: an F5 stego decoded by
     * JpegUniward, and a J-UNIWARD stego decoded by F5, both throw rather than returning garbage.
     */
    @Test
    public void testCrossPluginRejectionIsClean() throws Exception {
        byte[] f5Stego = embedF5();
        byte[] uniStego = embedPlainUniward();

        assertThrows(
                OpenStegoException.class,
                () -> extractWith("JpegUniward", f5Stego),
                "JpegUniward must reject an F5 stego");
        assertThrows(OpenStegoException.class, () -> extractWith("F5", uniStego), "F5 must reject a J-UNIWARD stego");

        // And each still decodes its own.
        assertArrayEquals(SECRET, (byte[]) extractWith("F5", f5Stego).get(1));
        assertArrayEquals(SECRET, (byte[]) extractWith("JpegUniward", uniStego).get(1));
    }

    private byte[] embedF5() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("F5");
        assertNotNull(plugin, "F5 plugin must be available");
        plugin.resetConfig();
        OpenStegoConfig config = plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(false);
        config.setPassword("pw-f5".toCharArray());
        return new OpenStego(plugin, config).embedData(SECRET, MSG_FILE_NAME, jpegCover, "cover.jpg", "stego.jpg");
    }

    private byte[] embedPlainUniward() throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        assertNotNull(plugin, "JpegUniward plugin must be available");
        plugin.resetConfig();
        JpegUniwardConfig config = (JpegUniwardConfig) plugin.getConfig();
        config.setUseCompression(true);
        config.setUseEncryption(false);
        config.setPlainMode(true);
        // Same password the extract helpers use: the band permutation is password-seeded, so embed and
        // extract must agree on it (this mirrors the auto-detect chain trying one password per plugin).
        config.setPassword("pw-f5".toCharArray());
        return new OpenStego(plugin, config).embedData(SECRET, MSG_FILE_NAME, jpegCover, "cover.jpg", "stego.jpg");
    }

    private List<?> extractWith(String pluginName, byte[] stego) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(pluginName);
        plugin.resetConfig();
        OpenStegoConfig config = plugin.getConfig();
        // Both producers above used a password for F5 and none for plain-uniward; supply both candidates.
        config.setPassword("pw-f5".toCharArray());
        return new OpenStego(plugin, config).extractData(stego, "stego.jpg");
    }

    private List<?> extractWithAutoDetect(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        OpenStegoException last = null;
        for (OpenStegoPlugin<?> plugin : orderPluginsForExtract(stegoFileName)) {
            plugin.resetConfig();
            OpenStegoConfig config = plugin.getConfig();
            config.setPassword("pw-f5".toCharArray());
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

    /** JPEG-capable plugins first for a .jpg input (JpegUniward, then F5), spatial plugins after. */
    private List<OpenStegoPlugin<?>> orderPluginsForExtract(String stegoFileName) {
        String lower = stegoFileName.toLowerCase();
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        List<OpenStegoPlugin<?>> ordered = new ArrayList<>();
        List<OpenStegoPlugin<?>> deferred = new ArrayList<>();
        for (OpenStegoPlugin<?> plugin : PluginManager.getDataHidingPlugins()) {
            boolean jpegPlugin = "JpegUniward".equals(plugin.getName()) || "F5".equals(plugin.getName());
            if (jpeg == jpegPlugin) {
                ordered.add(plugin);
            } else {
                deferred.add(plugin);
            }
        }
        ordered.addAll(deferred);
        return ordered;
    }

    private static boolean isJpeg(byte[] data) {
        return data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }
}
