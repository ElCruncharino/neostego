/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.gan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof that the GAN plugin's redundancy/error-correction layer (see {@link GanStegPlugin}'s
 * class Javadoc) actually recovers a message through the real bundled ONNX network, not just against a
 * mocked codec: runs the genuine {@code steganogan_dense_{encoder,decoder}.onnx} models via ONNX
 * Runtime on a real photographic cover. This is the "leave one runnable check" proof for the tiling +
 * majority-vote logic, which is non-trivial enough that a silent regression (wrong bit order, an
 * off-by-one in the tiling math, etc.) would otherwise only show up as a mysteriously unreliable feature.
 */
public class GanStegPluginTest {

    private static byte[] loadCover() throws IOException {
        try (InputStream is = GanStegPluginTest.class.getResourceAsStream("/compat/cover.png")) {
            return is.readAllBytes();
        }
    }

    /** A fresh plugin with an initialized (non-null), compression-disabled config. */
    private static GanStegPlugin newPlugin() throws OpenStegoException {
        GanStegPlugin plugin = new GanStegPlugin();
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false); // keep the message's on-wire size deterministic
        return plugin;
    }

    @Test
    public void roundTripsAShortMessageThroughTheRealNetwork() throws Exception {
        byte[] cover = loadCover();
        byte[] msg = "Hi neostego".getBytes(StandardCharsets.UTF_8);

        GanStegPlugin plugin = newPlugin();
        OpenStego embedder = new OpenStego(plugin, plugin.getConfig());
        byte[] stego = embedder.embedData(msg, null, cover, "cover.png", "stego.png");

        GanStegPlugin extractPlugin = newPlugin();
        OpenStego extractor = new OpenStego(extractPlugin, extractPlugin.getConfig());
        Object[] out = extractor.extractData(stego, "stego.png").toArray();
        byte[] extracted = (byte[]) out[1];

        assertArrayEquals(msg, extracted);
    }

    @Test
    public void rejectsAMessageThatDoesNotFitTheFixedBlock() throws Exception {
        byte[] cover = loadCover();
        byte[] tooLong = new byte[200]; // well past the ~64-byte header+payload budget
        Arrays.fill(tooLong, (byte) 'x');

        GanStegPlugin plugin = newPlugin();
        OpenStego embedder = new OpenStego(plugin, plugin.getConfig());
        OpenStegoException ex = assertThrows(
                OpenStegoException.class, () -> embedder.embedData(tooLong, null, cover, "cover.png", "stego.png"));
        assertTrue(ex.getErrorCode() == GanStegErrors.ERR_MESSAGE_TOO_LONG);
    }

    @Test
    public void writesAValidPngStegoFile(@TempDir Path tmp) throws Exception {
        byte[] cover = loadCover();
        GanStegPlugin plugin = newPlugin();
        OpenStego embedder = new OpenStego(plugin, plugin.getConfig());
        byte[] stego = embedder.embedData("ok".getBytes(StandardCharsets.UTF_8), null, cover, "cover.png", "stego.png");

        Path out = tmp.resolve("stego.png");
        Files.write(out, stego);
        assertTrue(ImageIO.read(out.toFile()) != null);
    }
}
