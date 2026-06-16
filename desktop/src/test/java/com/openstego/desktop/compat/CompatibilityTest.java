/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.compat;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Backward-compatibility regression tests.
 * <p>
 * The fixtures under {@code src/test/resources/compat/} are genuine v2 stego images produced by the
 * original (upstream) OpenStego data-embedding logic. These tests extract them with the current code
 * and assert that the recovered file name and bytes are exactly correct, including the password
 * (AES128) path through {@link com.openstego.desktop.OpenStegoCrypto}.
 * <p>
 * If anyone changes the on-disk stego format ({@code LSBDataHeader}) or the crypto parameters
 * ({@code OpenStegoCrypto}), these tests will fail - which is the point: existing files created by
 * older versions must remain decodable.
 */
public class CompatibilityTest {

    private static final String DH_PLUGIN = "RandomLSB";
    private static final String EXPECTED_FILE_NAME = "secret.txt";
    private static final String PASSWORD = "correct horse battery staple";

    private static byte[] expectedSecret;

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();
        expectedSecret = readResource("/compat/expected_secret.txt");
    }

    @Test
    public void testExtract_v2_plain() throws Exception {
        List<?> output = extract("/compat/randlsb_plain.png", "randlsb_plain.png", null);

        assertEquals(EXPECTED_FILE_NAME, output.get(0), "Embedded file name should be recovered");
        assertArrayEquals(expectedSecret, (byte[]) output.get(1), "Extracted bytes must match original payload");
    }

    @Test
    public void testExtract_v2_aes128() throws Exception {
        List<?> output = extract("/compat/randlsb_aes128.png", "randlsb_aes128.png", PASSWORD);

        assertEquals(EXPECTED_FILE_NAME, output.get(0), "Embedded file name should be recovered");
        assertArrayEquals(expectedSecret, (byte[]) output.get(1), "AES128-decrypted bytes must match original payload");
    }

    /**
     * Extracts the embedded data from a stego image bundled as a test resource, mirroring the
     * command-line extraction flow.
     */
    private List<?> extract(String resource, String stegoFileName, String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(DH_PLUGIN);
        assertNotNull(plugin, "Plugin '" + DH_PLUGIN + "' must be available");
        plugin.resetConfig();

        OpenStego stego = new OpenStego(plugin, plugin.getConfig());
        if (password != null) {
            stego.getConfig().setPassword(password);
        }

        byte[] stegoData = readResource(resource);
        return stego.extractData(stegoData, stegoFileName);
    }

    private static byte[] readResource(String path) throws Exception {
        try (InputStream is = CompatibilityTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "Test resource not found on classpath: " + path);
            return CommonUtil.streamToBytes(is);
        }
    }
}
