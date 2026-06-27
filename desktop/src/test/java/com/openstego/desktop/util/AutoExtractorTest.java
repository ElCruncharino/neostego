/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AutoExtractor}: container-gated, algorithm-agnostic extraction with an
 * invalid-password short-circuit. The key regression is that a PNG with a wrong password reports the
 * real "invalid password" error instead of the WAV plugin's "not a RIFF/WAVE container" red herring.
 */
public class AutoExtractorTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = AutoExtractorTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static byte[] embedEncrypted(String pluginName, byte[] msg, String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(pluginName);
        assertNotNull(plugin, pluginName + " plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseEncryption(true);
        plugin.getConfig().setPassword(password);
        byte[] stego = new OpenStego(plugin, plugin.getConfig())
                .embedData(msg, "secret.txt", coverBytes, "cover.png", "stego.png");
        plugin.getConfig().clearPassword();
        return stego;
    }

    /**
     * The reported bug: extracting from a PNG stego file with the wrong password surfaced the WAV
     * plugin's "not a RIFF/WAVE container" message, because the WAV plugin was tried (and failed) after
     * the spatial image plugins and overwrote the real error. Container gating now skips the WAV/JPEG
     * plugins for a PNG entirely, so the error reflects the image plugins instead.
     *
     * <p>Spatial LSB plugins seed their pixel permutation from the password, so a wrong password makes
     * the embedded header unreadable - indistinguishable from "no hidden data" - and they report
     * {@link OpenStegoErrors#NO_VALID_PLUGIN}, whose message explicitly names an invalid password as a
     * cause. Either way the audio red herring must be gone.
     */
    @Test
    public void wrongPasswordOnPngDoesNotReportWavError() throws Exception {
        byte[] stego = embedEncrypted("Adaptive", "top secret".getBytes(StandardCharsets.UTF_8), "rightpass");

        OpenStegoException ex = assertThrows(
                OpenStegoException.class,
                () -> AutoExtractor.extract(
                        stego, "stego.png", "wrongpass".toCharArray(), PluginManager.getDataHidingPlugins(), null));

        assertEquals(
                OpenStegoErrors.NO_VALID_PLUGIN,
                ex.getErrorCode(),
                "wrong password on a PNG must report the image-plugin error, not a WAV format error");
        assertFalse(
                ex.getMessage() != null && ex.getMessage().contains("RIFF"),
                "the WAV red-herring message must not surface for a PNG");
        assertTrue(
                ex.getMessage() != null && ex.getMessage().toLowerCase().contains("password"),
                "the surfaced error should name an invalid password as a possible cause");
    }

    /** With the right password, auto-detection across the full plugin set still round-trips a PNG. */
    @Test
    public void autoDetectRoundTripWithCorrectPassword() throws Exception {
        byte[] msg = "round-trip via auto-detect".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embedEncrypted("RandomLSB", msg, "pw");

        List<?> out = AutoExtractor.extract(
                stego, "stego.png", "pw".toCharArray(), PluginManager.getDataHidingPlugins(), null);

        assertEquals("secret.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }
}
