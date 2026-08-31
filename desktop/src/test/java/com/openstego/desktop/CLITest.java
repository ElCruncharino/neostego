/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.util.PluginManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the command-line interface ({@link OpenStegoCmd}). These guard the picocli-based parsing
 * and the end-to-end embed/extract flow, including a plugin-specific option and encryption.
 */
public class CLITest extends CmdTest {

    @BeforeAll
    public static void setUp() throws Exception {
        // Force OpenStego class initialization so its label namespace is registered, mirroring the
        // real entry point (OpenStego.main) which always loads before the command processor.
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    @Test
    public void testAlgorithmsListsPlugins() {
        String out = captureStdout(() -> OpenStegoCmd.execute(new String[] {"algorithms"}));
        assertTrue(out.contains("RandomLSB"), "'algorithms' output should list the RandomLSB plugin");
    }

    @Test
    public void testEmbedExtractRoundTrip(@TempDir Path dir) throws Exception {
        byte[] message = "command-line round-trip payload".getBytes(StandardCharsets.UTF_8);
        Path cover = copyResource("/compat/cover.png", dir.resolve("cover.png"));
        Path msg = dir.resolve("msg.txt");
        Files.write(msg, message);
        Path stego = dir.resolve("stego.png");
        Path outDir = dir.resolve("out");
        Files.createDirectories(outDir);

        OpenStegoCmd.execute(new String[] {
            "embed", "-a", "RandomLSB", "-mf", msg.toString(), "-cf", cover.toString(), "-sf", stego.toString()
        });
        assertTrue(Files.exists(stego), "Stego file should be created by embed");

        OpenStegoCmd.execute(
                new String[] {"extract", "-a", "RandomLSB", "-sf", stego.toString(), "-xd", outDir.toString()});

        byte[] extracted = Files.readAllBytes(outDir.resolve("msg.txt"));
        assertArrayEquals(message, extracted, "Extracted bytes should match the embedded message");
    }

    @Test
    public void testEmbedExtractWithPluginOptionAndEncryption(@TempDir Path dir) throws Exception {
        byte[] message = "secret with aes256 and 2 bits".getBytes(StandardCharsets.UTF_8);
        Path cover = copyResource("/compat/cover.png", dir.resolve("cover.png"));
        Path msg = dir.resolve("msg.txt");
        Files.write(msg, message);
        Path stego = dir.resolve("stego.png");
        Path outDir = dir.resolve("out");
        Files.createDirectories(outDir);

        OpenStegoCmd.execute(new String[] {
            "embed",
            "-a",
            "RandomLSB",
            "-mf",
            msg.toString(),
            "-cf",
            cover.toString(),
            "-sf",
            stego.toString(),
            "-b",
            "2",
            "-e",
            "-p",
            "s3cret",
            "-A",
            "AES256"
        });
        assertTrue(Files.exists(stego), "Stego file should be created by embed");

        OpenStegoCmd.execute(new String[] {
            "extract", "-a", "RandomLSB", "-sf", stego.toString(), "-xd", outDir.toString(), "-p", "s3cret"
        });

        byte[] extracted = Files.readAllBytes(outDir.resolve("msg.txt"));
        assertArrayEquals(message, extracted, "Extracted bytes should match for AES256 + 2 bits per channel");
    }

    @Test
    public void testSplitEmbedExtractRoundTrip(@TempDir Path dir) throws Exception {
        byte[] message = "payload split across several covers via the CLI".getBytes(StandardCharsets.UTF_8);
        Path c0 = copyResource("/compat/cover.png", dir.resolve("c0.png"));
        Path c1 = copyResource("/compat/cover.png", dir.resolve("c1.png"));
        Path c2 = copyResource("/compat/cover.png", dir.resolve("c2.png"));
        Path msg = dir.resolve("msg.txt");
        Files.write(msg, message);
        Path stegoDir = dir.resolve("stego");
        Files.createDirectories(stegoDir);
        Path outDir = dir.resolve("out");
        Files.createDirectories(outDir);

        String covers = String.join(";", c0.toString(), c1.toString(), c2.toString());
        OpenStegoCmd.execute(new String[] {
            "embed", "-a", "RandomLSB", "-S", "-mf", msg.toString(), "-cf", covers, "-sf", stegoDir.toString()
        });

        Path s0 = stegoDir.resolve("c0.png");
        Path s1 = stegoDir.resolve("c1.png");
        Path s2 = stegoDir.resolve("c2.png");
        assertTrue(
                Files.exists(s0) && Files.exists(s1) && Files.exists(s2), "One stego file per cover should be written");

        String parts = String.join(";", s0.toString(), s1.toString(), s2.toString());
        OpenStegoCmd.execute(new String[] {"extract", "-a", "RandomLSB", "-S", "-sf", parts, "-xd", outDir.toString()});

        byte[] extracted = Files.readAllBytes(outDir.resolve("msg.txt"));
        assertArrayEquals(message, extracted, "Split payload should reassemble to the original message");
    }

    @Test
    public void testSplitEmbedRequiresMultipleCovers(@TempDir Path dir) throws Exception {
        Path cover = copyResource("/compat/cover.png", dir.resolve("cover.png"));
        Path msg = dir.resolve("msg.txt");
        Files.write(msg, "x".getBytes(StandardCharsets.UTF_8));
        Path stegoDir = dir.resolve("stego");
        Files.createDirectories(stegoDir);

        String err = captureStderr(() -> OpenStegoCmd.execute(new String[] {
            "embed", "-a", "RandomLSB", "-S", "-mf", msg.toString(), "-cf", cover.toString(), "-sf", stegoDir.toString()
        }));
        assertTrue(err.contains("at least 2 cover"), "Split with one cover should be rejected; got: " + err);
    }

    @Test
    public void testSplitEmbedRequiresDirectoryOutput(@TempDir Path dir) throws Exception {
        Path c0 = copyResource("/compat/cover.png", dir.resolve("c0.png"));
        Path c1 = copyResource("/compat/cover.png", dir.resolve("c1.png"));
        Path msg = dir.resolve("msg.txt");
        Files.write(msg, "x".getBytes(StandardCharsets.UTF_8));
        Path stegoFile = dir.resolve("stego.png"); // a file, not a directory

        String covers = String.join(";", c0.toString(), c1.toString());
        String err = captureStderr(() -> OpenStegoCmd.execute(new String[] {
            "embed", "-a", "RandomLSB", "-S", "-mf", msg.toString(), "-cf", covers, "-sf", stegoFile.toString()
        }));
        assertTrue(err.contains("directory"), "Split embed to a non-directory output should be rejected; got: " + err);
    }
}
