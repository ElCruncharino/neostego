/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import com.openstego.desktop.OpenStegoException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Utility class to manipulate strings
 */
public class StringUtil {
    /**
     * Constructor is private so that this class is not instantiated
     */
    private StringUtil() {}

    /**
     * Method to convert byte array to hexadecimal string
     *
     * @param raw Raw byte array
     * @return Hex string
     */
    public static String getHexString(byte[] raw) {
        return HexFormat.of().formatHex(raw);
    }

    /**
     * Method to get the long hash from the password. This is used for seeding the random number generator
     *
     * @param password Password to hash
     * @return Long hash of the password
     */
    public static long passwordHash(char[] password) throws OpenStegoException {
        final long DEFAULT_HASH = 98234782; // Default to a random (but constant) seed
        byte[] byteHash;
        String hexString;

        if (password == null || password.length == 0) {
            return DEFAULT_HASH;
        }

        try {
            // Encode the char[] to UTF-8 bytes without creating an intermediate String
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            byteHash = MessageDigest.getInstance("MD5").digest(bytes);
            Arrays.fill(bytes, (byte) 0); // wipe the transient password bytes
            hexString = getHexString(byteHash);

            // Hex string will be 32 bytes long whereas parsing to long can handle only 16 bytes, so trim it
            hexString = hexString.substring(0, 15);
            return Long.parseLong(hexString, 16);
        } catch (NoSuchAlgorithmException nsaEx) {
            throw new OpenStegoException(nsaEx);
        }
    }

    /**
     * Method to tokenize a string by line breaks, trimming each line and dropping blank/comment
     * ("#"-prefixed) ones
     *
     * @param input Input string
     * @return List of strings tokenized by line breaks
     */
    public static List<String> getStringLines(String input) {
        return input.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
