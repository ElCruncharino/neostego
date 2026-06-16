/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link CommonUtil#parseFileList(String, String)}. Regression guard for upstream issue #60:
 * a single filename that contains the {@code ';'} list delimiter (common on Windows, where ';'
 * substitutes for the illegal ':' in timestamps) must not be split into several non-existent files.
 */
class CommonUtilTest {

    private static String path(File dir, String name) {
        return new File(dir, name).getPath();
    }

    /** A filename containing literal semicolons resolves to that one file, not several. */
    @Test
    void singleFilenameWithSemicolonsIsNotSplit(@TempDir Path tmp) throws Exception {
        File f = tmp.resolve("2023-02-23 16;29;20 - R5__0526.jpg").toFile();
        Files.writeString(f.toPath(), "x");

        List<File> result = CommonUtil.parseFileList(f.getPath(), ";");

        assertEquals(1, result.size(), "semicolon filename must stay a single file");
        assertEquals(f.getCanonicalPath(), result.get(0).getCanonicalFile().getPath());
    }

    /** A genuine multi-file list separated by ';' still expands to each file. */
    @Test
    void multiFileListStillSplits(@TempDir Path tmp) throws Exception {
        File a = tmp.resolve("a.png").toFile();
        File b = tmp.resolve("b.png").toFile();
        Files.writeString(a.toPath(), "a");
        Files.writeString(b.toPath(), "b");

        List<File> result = CommonUtil.parseFileList(path(tmp.toFile(), "a.png") + ";" + path(tmp.toFile(), "b.png"), ";");

        assertEquals(2, result.size());
    }

    /** Wildcards continue to expand against the directory. */
    @Test
    void wildcardsStillExpand(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("one.png"), "1");
        Files.writeString(tmp.resolve("two.png"), "2");
        Files.writeString(tmp.resolve("note.txt"), "n");

        List<File> result = CommonUtil.parseFileList(path(tmp.toFile(), "*.png"), ";");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(f -> f.getName().endsWith(".png")));
    }

    /** A backslash-escaped delimiter lets a multi-file list include a semicolon-bearing name. */
    @Test
    void escapedDelimiterKeepsSemicolonInName(@TempDir Path tmp) throws Exception {
        File semi = tmp.resolve("a;b.png").toFile();
        File plain = tmp.resolve("c.png").toFile();
        Files.writeString(semi.toPath(), "s");
        Files.writeString(plain.toPath(), "c");

        String list = path(tmp.toFile(), "a\\;b.png") + ";" + path(tmp.toFile(), "c.png");
        List<File> result = CommonUtil.parseFileList(list, ";");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(f -> f.getName().equals("a;b.png")));
        assertTrue(result.stream().anyMatch(f -> f.getName().equals("c.png")));
    }
}
