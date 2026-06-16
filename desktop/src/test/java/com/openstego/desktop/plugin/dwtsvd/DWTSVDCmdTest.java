/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoCmd;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end command-line tests for the DWT-SVD watermark, driving the real {@code gensig}, {@code embedmark} and
 * {@code checkmark} commands as a user would. Confirms that a freshly generated signature embeds and verifies with a
 * high correlation, and that an unrelated signature reads as absent.
 */
public class DWTSVDCmdTest {

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    @Test
    public void gensigEmbedmarkCheckmarkRoundTrip(@TempDir Path dir) throws Exception {
        Path cover = copyResource("/compat/cover.png", dir.resolve("cover.png"));
        Path sig = dir.resolve("key.sig");
        Path stego = dir.resolve("stego.png");

        OpenStegoCmd.execute(new String[]{
                "gensig", "-a", "DWTSVD", "-p", "cli-watermark-key", "-gf", sig.toString()});
        assertTrue(Files.exists(sig), "gensig should create the signature file");

        OpenStegoCmd.execute(new String[]{
                "embedmark", "-a", "DWTSVD", "-gf", sig.toString(), "-cf", cover.toString(), "-sf", stego.toString()});
        assertTrue(Files.exists(stego), "embedmark should create the watermarked image");

        double corr = parseDouble(captureStdout(() -> OpenStegoCmd.execute(new String[]{
                "checkmark", "-a", "DWTSVD", "-gf", sig.toString(), "-sf", stego.toString()})));
        assertTrue(corr > 0.999, "checkmark on the embedded image should be ~1.0, got " + corr);
    }

    @Test
    public void unrelatedSignatureReadsAsAbsent(@TempDir Path dir) throws Exception {
        Path cover = copyResource("/compat/cover.png", dir.resolve("cover.png"));
        Path sig = dir.resolve("key.sig");
        Path otherSig = dir.resolve("other.sig");
        Path stego = dir.resolve("stego.png");

        OpenStegoCmd.execute(new String[]{"gensig", "-a", "DWTSVD", "-p", "key-one", "-gf", sig.toString()});
        OpenStegoCmd.execute(new String[]{"gensig", "-a", "DWTSVD", "-p", "key-two", "-gf", otherSig.toString()});
        OpenStegoCmd.execute(new String[]{
                "embedmark", "-a", "DWTSVD", "-gf", sig.toString(), "-cf", cover.toString(), "-sf", stego.toString()});

        double corr = parseDouble(captureStdout(() -> OpenStegoCmd.execute(new String[]{
                "checkmark", "-a", "DWTSVD", "-gf", otherSig.toString(), "-sf", stego.toString()})));
        assertTrue(corr < 0.2, "an unrelated signature must read as absent, got " + corr);
    }

    private static Path copyResource(String resource, Path target) throws Exception {
        try (InputStream is = DWTSVDCmdTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "Test resource not found: " + resource);
            Files.write(target, CommonUtil.streamToBytes(is));
        }
        return target;
    }

    private static double parseDouble(String stdout) {
        String[] lines = stdout.trim().split("\\R");
        return Double.parseDouble(lines[lines.length - 1].trim());
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
