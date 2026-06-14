package com.openstego.desktop;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the command-line interface ({@link OpenStegoCmd}). These guard the picocli-based parsing
 * and the end-to-end embed/extract flow, including a plugin-specific option and encryption.
 */
public class CLITest {

    @BeforeAll
    public static void setUp() throws Exception {
        // Force OpenStego class initialization so its label namespace is registered, mirroring the
        // real entry point (OpenStego.main) which always loads before the command processor.
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
    }

    @Test
    public void testAlgorithmsListsPlugins() {
        String out = captureStdout(() -> OpenStegoCmd.execute(new String[]{"algorithms"}));
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

        OpenStegoCmd.execute(new String[]{
                "embed", "-a", "RandomLSB", "-mf", msg.toString(), "-cf", cover.toString(), "-sf", stego.toString()});
        assertTrue(Files.exists(stego), "Stego file should be created by embed");

        OpenStegoCmd.execute(new String[]{
                "extract", "-a", "RandomLSB", "-sf", stego.toString(), "-xd", outDir.toString()});

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

        OpenStegoCmd.execute(new String[]{
                "embed", "-a", "RandomLSB", "-mf", msg.toString(), "-cf", cover.toString(), "-sf", stego.toString(),
                "-b", "2", "-e", "-p", "s3cret", "-A", "AES256"});
        assertTrue(Files.exists(stego), "Stego file should be created by embed");

        OpenStegoCmd.execute(new String[]{
                "extract", "-a", "RandomLSB", "-sf", stego.toString(), "-xd", outDir.toString(), "-p", "s3cret"});

        byte[] extracted = Files.readAllBytes(outDir.resolve("msg.txt"));
        assertArrayEquals(message, extracted, "Extracted bytes should match for AES256 + 2 bits per channel");
    }

    private static Path copyResource(String resource, Path target) throws Exception {
        try (InputStream is = CLITest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "Test resource not found: " + resource);
            Files.write(target, CommonUtil.streamToBytes(is));
        }
        return target;
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
