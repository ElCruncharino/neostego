/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import com.openstego.desktop.OpenStegoException;
import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Common utilities for OpenStego
 */
public class CommonUtil {
    /**
     * Constructor is private so that this class is not instantiated
     */
    private CommonUtil() {}

    /**
     * Method to get byte array data from given InputStream
     *
     * @param is InputStream to read
     * @return Stream data as byte array
     * @throws OpenStegoException Processing issues
     */
    public static byte[] streamToBytes(InputStream is) throws OpenStegoException {
        try {
            return is.readAllBytes();
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
    }

    /**
     * Method to get byte array data from given file
     *
     * @param file File to read
     * @return File data as byte array
     * @throws OpenStegoException Processing issues
     */
    public static byte[] fileToBytes(File file) throws OpenStegoException {
        // Read in a single exact-size allocation rather than a doubling ByteArrayOutputStream: the latter
        // can transiently hold ~2-3x the file in memory (old buffer + grown buffer + final copy), which is
        // a big factor in OutOfMemoryError on large inputs (upstream issue #67).
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
    }

    /**
     * Method to write file data to disk
     *
     * @param fileData File data
     * @param fileName File name (If this is <code>null</code>, then data is written to stdout)
     * @throws OpenStegoException Processing issues
     */
    public static void writeFile(byte[] fileData, String fileName) throws OpenStegoException {
        File file = null;

        if (fileName != null) {
            file = new File(fileName);
        }
        writeFile(fileData, file);
    }

    /**
     * Method to write file data to disk
     *
     * @param fileData File data
     * @param file     File object (If this is <code>null</code>, then data is written to stdout)
     * @throws OpenStegoException Processing issues
     */
    public static void writeFile(byte[] fileData, File file) throws OpenStegoException {
        // If file is not provided, then write the data to stdout
        try (OutputStream os = (file == null ? System.out : new FileOutputStream(file))) {
            os.write(fileData);
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }
    }

    /**
     * Method to parse a delimiter separated list of files into arraylist of filenames. It supports wildcard characters
     * "*" and "?" within the filenames.
     *
     * @param fileList  Delimiter separated list of filenames
     * @param delimiter Delimiter for tokenization
     * @return List of filenames after tokenizing and wildcard expansion
     */
    public static List<File> parseFileList(String fileList, String delimiter) {
        List<File> output = new ArrayList<>();

        if (fileList == null) {
            return output;
        }

        // If the whole string is itself an existing file, use it verbatim without tokenizing. This
        // lets a single filename contain the delimiter (e.g. "2023-02-23 16;29;20 - R5.jpg"), which
        // is common on Windows where ';' substitutes for the illegal ':' in timestamps.
        File whole = new File(fileList);
        if (whole.isFile()) {
            output.add(whole);
            return output;
        }

        char delim = delimiter.isEmpty() ? ';' : delimiter.charAt(0);
        for (String rawToken : splitEscaped(fileList, delim)) {
            String fileName = rawToken.trim();
            if (fileName.isEmpty()) {
                continue;
            }

            int index = fileName.lastIndexOf(File.separator);
            String dirName;
            if (index >= 0) {
                dirName = fileName.substring(0, index);
                fileName = fileName.substring(index + 1);
            } else {
                dirName = ".";
            }
            boolean hasWildcard = fileName.indexOf('*') >= 0 || fileName.indexOf('?') >= 0;

            File fileDir = new File(dirName.isEmpty() ? "." : dirName);
            // Glob matching is case-sensitive on most platforms; lower-case both sides to match the
            // case-insensitive semantics of the original hand-rolled regex filter.
            java.nio.file.PathMatcher matcher =
                    FileSystems.getDefault().getPathMatcher("glob:" + fileName.toLowerCase());
            File[] arrFile = fileDir.listFiles((dir, name) -> matcher.matches(Paths.get(name.toLowerCase())));
            if (arrFile != null && arrFile.length > 0) {
                Collections.addAll(output, arrFile);
            } else if (!hasWildcard) {
                // No wildcard expansion matched: treat the token as a literal path. Covers an
                // escaped delimiter ("a\;b.png") and yields a clear "file not found" downstream
                // instead of silently dropping the entry.
                output.add(new File(dirName.isEmpty() ? "." : dirName, fileName));
            }
        }

        return output;
    }

    /**
     * Splits a delimited list while honouring a backslash escape, so that a delimiter preceded by a
     * backslash ("\;") is treated as a literal character within a single filename rather than a
     * separator. A bare backslash (e.g. a Windows path separator) is preserved unchanged.
     *
     * @param input Delimited input string
     * @param delim Delimiter character
     * @return List of tokens with escaped delimiters un-escaped
     */
    private static List<String> splitEscaped(String input, char delim) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < input.length() && input.charAt(i + 1) == delim) {
                cur.append(delim);
                i++;
            } else if (c == delim) {
                tokens.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        tokens.add(cur.toString());
        return tokens;
    }

    /**
     * Byte to Int converter
     *
     * @param b Input byte value
     * @return Int value
     */
    public static int byteToInt(int b) {
        return Byte.toUnsignedInt((byte) b);
    }

    /**
     * Returns the floor of the half of the input value
     *
     * @param num Input number
     * @return Floor of the half of the input number
     */
    public static int floorHalf(int num) {
        return Math.floorDiv(num, 2);
    }

    /**
     * Returns the ceiling of the half of the input value
     *
     * @param num Input number
     * @return Ceiling of the half of the input number
     */
    public static int ceilingHalf(int num) {
        return Math.floorDiv(num + 1, 2);
    }

    /**
     * Returns the modulus of the input value (taking care of the sign of the value)
     *
     * @param num Input number
     * @param div Divisor for modulus
     * @return Modulus of num by div
     */
    public static int mod(int num, int div) {
        return Math.floorMod(num, div);
    }
}
