/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openstego.desktop.util.CommonUtil;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared helpers for CLI-driven end-to-end tests: copying a classpath resource into a temp file, and
 * capturing stdout/stderr around a picocli command invocation.
 */
public abstract class CmdTest {

    protected static Path copyResource(String resource, Path target) throws Exception {
        try (InputStream is = CmdTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "Test resource not found: " + resource);
            Files.write(target, CommonUtil.streamToBytes(is));
        }
        return target;
    }

    protected static String captureStdout(Runnable action) {
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

    protected static String captureStderr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
