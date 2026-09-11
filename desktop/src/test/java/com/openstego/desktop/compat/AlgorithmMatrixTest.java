/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoCrypto;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig;
import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.PluginManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Cross-product coverage for every data-hiding algorithm x every embed option x single-cover and
 * split-across-covers x hide and reveal. Motivated by two real crashes (issue #50): both were gaps
 * that only manifested for a specific algorithm/split combination, and neither was covered by any
 * existing test because each algorithm's tests only ever exercised it in isolation, never crossed with
 * "and also split, and also a wrong password." This is the systematic sweep meant to make that class
 * of gap show up here instead of in a bug report.
 *
 * <p>Each of the two {@code @Test} methods below runs every combination in its matrix and collects
 * every failure before reporting (rather than stopping at the first one), so a single run shows the
 * full extent of any regression across the matrix, not just its first symptom.
 */
public class AlgorithmMatrixTest {

    private static final String MSG_FILE_NAME = "secret.bin";
    private static final String PASSWORD = "correct-password";
    private static final String WRONG_PASSWORD = "not-the-password";

    /** One data-hiding configuration under test. JpegUniward covers both its SI and plain modes. */
    private record Scenario(String pluginName, boolean plainUniward, boolean needsJpegCover) {
        private static Scenario of(String pluginName) {
            return new Scenario(pluginName, false, false);
        }

        private static Scenario jpegUniwardPlain() {
            return new Scenario("JpegUniward", true, true);
        }

        private static Scenario jpegCover(String pluginName) {
            return new Scenario(pluginName, false, true);
        }

        @Override
        public String toString() {
            return plainUniward ? "JpegUniward(plain)" : pluginName;
        }
    }

    private enum Encryption {
        NONE,
        AES128,
        AES256
    }

    // Plain "LSB" is deliberately not in this list: it's registered only as RandomLSBPlugin's base
    // class (see core/src/main/resources/OpenStegoPlugins.internal), never as a selectable plugin in
    // its own right - PluginManager.getPluginByName("LSB") returns null by design.
    private static final List<Scenario> SCENARIOS = List.of(
            Scenario.of("RandomLSB"),
            Scenario.of("RandomLSBMatch"),
            Scenario.of("Adaptive"),
            Scenario.of("JpegUniward"), // SI-UNIWARD: uncompressed PNG precover
            Scenario.jpegUniwardPlain(), // plain J-UNIWARD: already-compressed JPEG cover
            Scenario.jpegCover("F5"));

    @BeforeAll
    public static void setUp() throws Exception {
        PluginManager.loadPlugins();
    }

    @SuppressWarnings("unchecked")
    private static DHImagePluginTemplate<OpenStegoConfig> freshPlugin(
            Scenario scenario, char[] password, Encryption enc) throws OpenStegoException {
        OpenStegoPlugin<?> raw = PluginManager.getPluginByName(scenario.pluginName());
        assertNotNull(raw, scenario.pluginName() + " must be registered");
        raw.resetConfig();
        DHImagePluginTemplate<OpenStegoConfig> plugin = (DHImagePluginTemplate<OpenStegoConfig>) raw;
        if (plugin.getConfig() instanceof JpegUniwardConfig juConfig) {
            juConfig.setPlainMode(scenario.plainUniward());
        }
        if (password != null) {
            OpenStegoConfig config = plugin.getConfig();
            config.setUseEncryption(true);
            config.setPassword(password.clone());
            if (enc == Encryption.AES256) {
                config.setEncryptionAlgorithm(OpenStegoCrypto.ALGO_AES256);
            }
        }
        return plugin;
    }

    private static byte[] pngCover() throws OpenStegoException {
        PixelImage image = ImageCodecRegistry.get().createRandomImage(150_000);
        return ImageCodecRegistry.get().encode(image, "cover.png");
    }

    private static byte[] jpegCover() throws OpenStegoException {
        PixelImage precover = ImageCodecRegistry.get().decode(pngCover(), "cover.png");
        return JpegCodec.encode(JpegCodec.fromPrecover(precover, 90));
    }

    private static byte[] cover(Scenario scenario) throws OpenStegoException {
        return scenario.needsJpegCover() ? jpegCover() : pngCover();
    }

    private static String coverExt(Scenario scenario) {
        return scenario.needsJpegCover() ? "jpg" : "png";
    }

    private static byte[] randomPayload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data); // incompressible, so compression on/off both get exercised meaningfully
        return data;
    }

    private static List<String> names(Scenario scenario, int count, String base) {
        List<String> out = new ArrayList<>(count);
        String ext = coverExt(scenario);
        for (int i = 0; i < count; i++) {
            out.add(base + i + "." + ext);
        }
        return out;
    }

    /** Embeds+extracts one combination with the correct password; throws with a descriptive message
     *  identifying the failing combination if anything goes wrong. */
    private static void roundTrip(Scenario scenario, Encryption enc, boolean compress, boolean split, long seed)
            throws Exception {
        char[] password = enc == Encryption.NONE ? null : PASSWORD.toCharArray();
        byte[] payload = randomPayload(400, seed);

        DHImagePluginTemplate<OpenStegoConfig> embedPlugin = freshPlugin(scenario, password, enc);
        embedPlugin.getConfig().setUseCompression(compress);

        if (!split) {
            byte[] cover = cover(scenario);
            byte[] stego = new OpenStego(embedPlugin, embedPlugin.getConfig())
                    .embedData(
                            payload,
                            MSG_FILE_NAME,
                            cover,
                            "cover." + coverExt(scenario),
                            "stego." + coverExt(scenario));

            DHImagePluginTemplate<OpenStegoConfig> extractPlugin = freshPlugin(scenario, password, enc);
            List<?> out = new OpenStego(extractPlugin, extractPlugin.getConfig())
                    .extractData(stego, "stego." + coverExt(scenario));
            assertEquals(MSG_FILE_NAME, out.get(0));
            assertArrayEquals(payload, (byte[]) out.get(1));
        } else {
            List<byte[]> covers = List.of(cover(scenario), cover(scenario), cover(scenario));
            List<String> coverNames = names(scenario, 3, "cover");
            List<String> stegoNames = names(scenario, 3, "stego");
            List<byte[]> stegoParts = MultiCoverPayloadSplitter.embedSplit(
                    payload, MSG_FILE_NAME, covers, coverNames, stegoNames, embedPlugin.getConfig(), embedPlugin);

            DHImagePluginTemplate<OpenStegoConfig> extractPlugin = freshPlugin(scenario, password, enc);
            List<?> out = MultiCoverPayloadSplitter.extractSplit(
                    stegoParts, stegoNames, extractPlugin.getConfig(), extractPlugin);
            assertEquals(MSG_FILE_NAME, out.get(0));
            assertArrayEquals(payload, (byte[]) out.get(1));
        }
    }

    @Test
    public void roundTripMatrix() {
        List<String> failures = new ArrayList<>();
        long seed = 0;
        for (Scenario scenario : SCENARIOS) {
            for (Encryption enc : Encryption.values()) {
                for (boolean compress : new boolean[] {true, false}) {
                    for (boolean split : new boolean[] {false, true}) {
                        String label = scenario + " enc=" + enc + " compress=" + compress + " split=" + split;
                        try {
                            roundTrip(scenario, enc, compress, split, seed++);
                        } catch (Throwable t) {
                            failures.add(label + " -> " + t);
                        }
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + (SCENARIOS.size() * Encryption.values().length * 4)
                    + " combinations failed:\n" + String.join("\n", failures));
        }
    }

    /**
     * The actual regression target: a wrong password must produce a clean {@link OpenStegoException}
     * with a real message, never an uncaught crash while building that message. Both issue #50 bugs
     * were exactly this - the exception itself failed to construct.
     */
    private static void wrongPasswordFailsCleanly(Scenario scenario, Encryption enc, boolean split, long seed)
            throws Exception {
        byte[] payload = randomPayload(400, seed);
        DHImagePluginTemplate<OpenStegoConfig> embedPlugin = freshPlugin(scenario, PASSWORD.toCharArray(), enc);

        if (!split) {
            byte[] cover = cover(scenario);
            byte[] stego = new OpenStego(embedPlugin, embedPlugin.getConfig())
                    .embedData(
                            payload,
                            MSG_FILE_NAME,
                            cover,
                            "cover." + coverExt(scenario),
                            "stego." + coverExt(scenario));

            DHImagePluginTemplate<OpenStegoConfig> extractPlugin =
                    freshPlugin(scenario, WRONG_PASSWORD.toCharArray(), enc);
            OpenStegoException ex =
                    assertThrows(OpenStegoException.class, () -> new OpenStego(extractPlugin, extractPlugin.getConfig())
                            .extractData(stego, "stego." + coverExt(scenario)));
            assertNotNull(ex.getMessage(), "exception message must build without crashing");
        } else {
            List<byte[]> covers = List.of(cover(scenario), cover(scenario), cover(scenario));
            List<String> coverNames = names(scenario, 3, "cover");
            List<String> stegoNames = names(scenario, 3, "stego");
            List<byte[]> stegoParts = MultiCoverPayloadSplitter.embedSplit(
                    payload, MSG_FILE_NAME, covers, coverNames, stegoNames, embedPlugin.getConfig(), embedPlugin);

            DHImagePluginTemplate<OpenStegoConfig> extractPlugin =
                    freshPlugin(scenario, WRONG_PASSWORD.toCharArray(), enc);
            OpenStegoException ex = assertThrows(
                    OpenStegoException.class,
                    () -> MultiCoverPayloadSplitter.extractSplit(
                            stegoParts, stegoNames, extractPlugin.getConfig(), extractPlugin));
            assertNotNull(ex.getMessage(), "exception message must build without crashing");
        }
    }

    @Test
    public void wrongPasswordMatrix() {
        List<String> failures = new ArrayList<>();
        long seed = 10_000;
        int total = 0;
        for (Scenario scenario : SCENARIOS) {
            for (Encryption enc : new Encryption[] {Encryption.AES128, Encryption.AES256}) {
                for (boolean split : new boolean[] {false, true}) {
                    total++;
                    String label = scenario + " enc=" + enc + " split=" + split;
                    try {
                        wrongPasswordFailsCleanly(scenario, enc, split, seed++);
                    } catch (Throwable t) {
                        failures.add(label + " -> " + t);
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + total + " combinations failed:\n" + String.join("\n", failures));
        }
    }
}
