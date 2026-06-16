/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.wavlsb;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the WAV LSB audio plugin (upstream issue #5: audio support). A message hidden in a
 * PCM WAV must round-trip, the stego file must remain a valid same-format WAV, oversized payloads must fail
 * cleanly, and compression+encryption must still work through the standard OpenStego pipeline.
 */
public class WavLSBPluginTest {

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    /** Builds a 16-bit mono PCM WAV with {@code numSamples} pseudo-random samples. */
    private static byte[] makeWav(int numSamples) {
        int bytesPerSample = 2;
        int dataLen = numSamples * bytesPerSample;
        int sampleRate = 44100;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeTag(o, "RIFF");
        writeLE32(o, 36 + dataLen);
        writeTag(o, "WAVE");
        writeTag(o, "fmt ");
        writeLE32(o, 16);
        writeLE16(o, 1);            // PCM
        writeLE16(o, 1);            // mono
        writeLE32(o, sampleRate);
        writeLE32(o, sampleRate * bytesPerSample);
        writeLE16(o, bytesPerSample);
        writeLE16(o, 16);           // bits per sample
        writeTag(o, "data");
        writeLE32(o, dataLen);
        Random rnd = new Random(42L);
        for (int i = 0; i < numSamples; i++) {
            int s = (short) rnd.nextInt();
            o.write(s & 0xFF);
            o.write((s >> 8) & 0xFF);
        }
        return o.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream o, String t) {
        for (int i = 0; i < t.length(); i++) {
            o.write(t.charAt(i));
        }
    }

    private static void writeLE16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static OpenStego newStego(boolean compress, boolean encrypt, String password) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("WavLSB");
        assertNotNull(plugin, "WavLSB plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(compress);
        plugin.getConfig().setUseEncryption(encrypt);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void roundTripsMessageAndKeepsValidWav() throws Exception {
        byte[] cover = makeWav(50_000);
        byte[] msg = "hidden in audio - issue #5".getBytes(StandardCharsets.UTF_8);

        byte[] stego = newStego(false, false, null).embedData(msg, "secret.txt", cover, "cover.wav", "stego.wav");

        // Same container size/format - it still plays.
        assertEquals(cover.length, stego.length, "stego WAV must be the same size/format as the cover");
        assertEquals('R', stego[0]);
        assertEquals('I', stego[1]);

        List<?> out = newStego(false, false, null).extractData(stego, "stego.wav");
        assertEquals("secret.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void roundTripsWithCompressionAndEncryption() throws Exception {
        byte[] cover = makeWav(80_000);
        byte[] msg = "encrypted + compressed audio payload".getBytes(StandardCharsets.UTF_8);

        byte[] stego = newStego(true, true, "pw").embedData(msg, "m.txt", cover, "cover.wav", "stego.wav");
        List<?> out = newStego(true, true, "pw").extractData(stego, "stego.wav");

        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void oversizedPayloadFailsCleanly() throws Exception {
        byte[] cover = makeWav(1_000); // ~125 bytes capacity
        byte[] huge = new byte[10_000];

        assertThrows(OpenStegoException.class,
                () -> newStego(false, false, null).embedData(huge, "big.bin", cover, "cover.wav", "stego.wav"));
    }

    @Test
    public void nonWavCoverFailsCleanly() throws Exception {
        byte[] notWav = new byte[100];
        assertThrows(OpenStegoException.class,
                () -> newStego(false, false, null).embedData(new byte[]{1, 2, 3}, "m", notWav, "cover.wav", "stego.wav"));
    }

    @Test
    public void exposesWavExtensions() throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("WavLSB");
        assertTrue(plugin.getReadableFileExtensions().contains("wav"));
        assertTrue(plugin.getWritableFileExtensions().contains("wav"));
    }
}
