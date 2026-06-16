/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link OpenStegoCrypto}, covering the modern (v3) AES-GCM format and the legacy (v2)
 * format, including auto-detection on decrypt.
 */
public class OpenStegoCryptoTest {

    private static final byte[] PLAIN = "The quick brown fox jumps over the lazy dog. 0123456789".getBytes(StandardCharsets.UTF_8);
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @BeforeAll
    public static void setUp() throws Exception {
        // Ensure label namespaces / error codes are registered (OpenStego.main does this in production)
        Class.forName(OpenStego.class.getName());
    }

    @Test
    public void testV3RoundTrip_aes128() throws Exception {
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES128, true);
        byte[] enc = crypto.encrypt(PLAIN);
        assertEquals(0, enc[0], "v3 payload must start with the zero marker byte");
        assertArrayEquals(PLAIN, crypto.decrypt(enc));
    }

    @Test
    public void testV3RoundTrip_aes256() throws Exception {
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES256, true);
        byte[] enc = crypto.encrypt(PLAIN);
        assertEquals(0, enc[0], "v3 payload must start with the zero marker byte");
        assertArrayEquals(PLAIN, crypto.decrypt(enc));
    }

    @Test
    public void testV3WrongPasswordFails() throws Exception {
        byte[] enc = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES256, true).encrypt(PLAIN);
        OpenStegoCrypto wrong = new OpenStegoCrypto("not the password".toCharArray(), OpenStegoCrypto.ALGO_AES256, true);
        OpenStegoException ex = assertThrows(OpenStegoException.class, () -> wrong.decrypt(enc));
        assertEquals(OpenStegoErrors.INVALID_PASSWORD, ex.getErrorCode());
    }

    @Test
    public void testV3IsRandomized() throws Exception {
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES256, true);
        byte[] enc1 = crypto.encrypt(PLAIN);
        byte[] enc2 = crypto.encrypt(PLAIN);
        // Random salt + IV mean two encryptions of the same data differ
        assertFalse(java.util.Arrays.equals(enc1, enc2), "v3 ciphertexts should differ due to random salt/IV");
    }

    @Test
    public void testLegacyV2RoundTrip() throws Exception {
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES128, false);
        byte[] enc = crypto.encrypt(PLAIN);
        assertNotEquals(0, enc[0], "v2 payload starts with a non-zero parameter length");
        assertArrayEquals(PLAIN, crypto.decrypt(enc));
    }

    @Test
    public void testDecryptAutoDetectsV2WhenStrongEnabled() throws Exception {
        // Encrypt with legacy v2, then decrypt using a strong-enabled instance: decrypt must auto-detect v2
        byte[] enc = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES128, false).encrypt(PLAIN);
        byte[] dec = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES128, true).decrypt(enc);
        assertArrayEquals(PLAIN, dec);
    }

    @Test
    public void testLegacyV2RoundTrip_aes256() throws Exception {
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_AES256, false);
        byte[] enc = crypto.encrypt(PLAIN);
        assertNotEquals(0, enc[0], "v2 payload starts with a non-zero parameter length");
        assertArrayEquals(PLAIN, crypto.decrypt(enc));
    }

    @Test
    public void testDesRejectedForEncryption() throws Exception {
        // DES is cryptographically broken and no longer allowed for new encryption (read-only)
        OpenStegoCrypto crypto = new OpenStegoCrypto(PASSWORD, OpenStegoCrypto.ALGO_DES, true);
        assertThrows(OpenStegoException.class, () -> crypto.encrypt(PLAIN));
    }
}
