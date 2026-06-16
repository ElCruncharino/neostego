/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.util.ecc;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for the {@link ReedSolomon} GF(256) codec. The decisive property is that any error pattern of up to
 * {@code nParity/2} byte errors is corrected, and that uncorrectable patterns degrade gracefully rather than corrupt
 * silently. We hammer the codec with thousands of randomized error patterns.
 */
class ReedSolomonTest {

    @Test
    void cleanRoundTrip() {
        Random rnd = new Random(1);
        for (int trial = 0; trial < 500; trial++) {
            ReedSolomon rs = new ReedSolomon(2 + rnd.nextInt(20));
            byte[] data = new byte[1 + rnd.nextInt(64)];
            rnd.nextBytes(data);
            byte[] code = rs.encode(data);
            assertEquals(data.length + rs.getParityLength(), code.length);
            assertTrue(rs.isCorrectable(code));
            assertArrayEquals(data, rs.decode(code));
        }
    }

    @Test
    void correctsUpToTErrors() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 5000; trial++) {
            int nParity = 4 + 2 * rnd.nextInt(12); // even, 4..26
            int t = nParity / 2;
            ReedSolomon rs = new ReedSolomon(nParity);
            byte[] data = new byte[8 + rnd.nextInt(40)];
            rnd.nextBytes(data);
            byte[] code = rs.encode(data);

            // Inject exactly t errors at distinct positions.
            int numErrors = rnd.nextInt(t + 1); // 0..t
            byte[] corrupted = code.clone();
            Set<Integer> positions = new HashSet<>();
            while (positions.size() < numErrors) {
                positions.add(rnd.nextInt(code.length));
            }
            for (int p : positions) {
                int delta = 1 + rnd.nextInt(255);
                corrupted[p] = (byte) ((corrupted[p] & 0xff) ^ delta);
            }

            assertTrue(rs.isCorrectable(corrupted),
                    "should correct " + numErrors + " errors with t=" + t);
            assertArrayEquals(data, rs.decode(corrupted),
                    "decoded mismatch with " + numErrors + " errors, t=" + t);
        }
    }

    @Test
    void gracefulOnUncorrectable() {
        // With t errors correctable, injecting many more must not throw and must not claim success.
        Random rnd = new Random(7);
        ReedSolomon rs = new ReedSolomon(4); // t = 2
        byte[] data = new byte[20];
        rnd.nextBytes(data);
        byte[] code = rs.encode(data);
        byte[] corrupted = code.clone();
        for (int i = 0; i < code.length; i++) {
            corrupted[i] = (byte) ((corrupted[i] & 0xff) ^ (1 + rnd.nextInt(255)));
        }
        assertFalse(rs.isCorrectable(corrupted));
        // decode() must return *something* of the right length without throwing.
        assertEquals(data.length, rs.decode(corrupted).length);
    }
}
