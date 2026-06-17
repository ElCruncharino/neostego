/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive correctness tests for {@link Stc}. The critical property is recoverability:
 * {@code extract(embed(x, rho, m)) == m} for every input, because an STC bug manifests as
 * unrecoverable hidden data. We hammer it with thousands of randomized cases plus edge sizes, verify
 * determinism, and cross-check the syndrome against an independently built parity-check matrix.
 */
class StcTest {

    private static final int H = Stc.DEFAULT_HEIGHT;

    @Test
    void roundTripExhaustive() {
        Random seed = new Random(12345);
        for (int trial = 0; trial < 5000; trial++) {
            Random r = new Random(seed.nextLong());
            int w = 1 + r.nextInt(8); // payload denominator 1..8
            int messageLen = 1 + r.nextInt(256);
            int n = messageLen * w;

            int[] x = new int[n];
            double[] rho = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = r.nextInt(2);
                rho[i] = 0.01 + r.nextDouble(); // strictly positive cost
            }
            int[] message = new int[messageLen];
            for (int i = 0; i < messageLen; i++) {
                message[i] = r.nextInt(2);
            }

            int[] y = Stc.embed(x, rho, message, w, H);
            assertEquals(n, y.length, "stego length");
            for (int v : y) {
                assertTrue(v == 0 || v == 1, "stego is binary");
            }
            int[] recovered = Stc.extract(y, messageLen, w, H);
            assertArrayEquals(message, recovered, "round-trip failed: w=" + w + " messageLen=" + messageLen);
        }
    }

    @Test
    void deterministic() {
        Random r = new Random(99);
        int w = 3;
        int messageLen = 100;
        int n = messageLen * w;
        int[] x = new int[n];
        double[] rho = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = r.nextInt(2);
            rho[i] = 0.01 + r.nextDouble();
        }
        int[] message = new int[messageLen];
        for (int i = 0; i < messageLen; i++) {
            message[i] = r.nextInt(2);
        }
        int[] y1 = Stc.embed(x, rho, message, w, H);
        int[] y2 = Stc.embed(x, rho, message, w, H);
        assertArrayEquals(y1, y2, "embedding must be deterministic");
    }

    @Test
    void edgeCases() {
        // Single message bit at various widths, and full payload (w == 1)
        int[][] params = {{1, 1}, {1, 8}, {2, 1}, {300, 1}, {7, 5}, {255, 2}};
        Random r = new Random(7);
        for (int[] p : params) {
            int messageLen = p[0];
            int w = p[1];
            int n = messageLen * w;
            int[] x = new int[n];
            double[] rho = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = r.nextInt(2);
                rho[i] = 0.01 + r.nextDouble();
            }
            int[] message = new int[messageLen];
            for (int i = 0; i < messageLen; i++) {
                message[i] = r.nextInt(2);
            }
            int[] y = Stc.embed(x, rho, message, w, H);
            assertArrayEquals(
                    message,
                    Stc.extract(y, messageLen, w, H),
                    "edge case failed: messageLen=" + messageLen + " w=" + w);
        }
    }

    @Test
    void syndromeMatchesIndependentMatrix() {
        // Build H explicitly from the banded definition and confirm extract() == H*y, and that the
        // embedded y satisfies H*y == message, using a different code path than Stc itself.
        Random r = new Random(2024);
        for (int trial = 0; trial < 200; trial++) {
            int w = 1 + r.nextInt(4);
            int messageLen = 1 + r.nextInt(40);
            int n = messageLen * w;
            int[] hhat = Stc.buildSubmatrix(H, w);

            // H[row][p] booleans
            boolean[][] hMat = new boolean[messageLen][n];
            for (int p = 0; p < n; p++) {
                int b = p / w;
                int col = hhat[p % w];
                for (int t = 0; t < H; t++) {
                    int row = b + t;
                    if (row < messageLen && ((col >> t) & 1) != 0) {
                        hMat[row][p] = true;
                    }
                }
            }

            int[] x = new int[n];
            double[] rho = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = r.nextInt(2);
                rho[i] = 0.01 + r.nextDouble();
            }
            int[] message = new int[messageLen];
            for (int i = 0; i < messageLen; i++) {
                message[i] = r.nextInt(2);
            }

            int[] y = Stc.embed(x, rho, message, w, H);

            int[] syndrome = new int[messageLen];
            for (int row = 0; row < messageLen; row++) {
                int acc = 0;
                for (int p = 0; p < n; p++) {
                    if (hMat[row][p] && y[p] == 1) {
                        acc ^= 1;
                    }
                }
                syndrome[row] = acc;
            }
            assertArrayEquals(message, syndrome, "independent H*y != message");
            assertArrayEquals(syndrome, Stc.extract(y, messageLen, w, H), "extract != H*y");
        }
    }

    @Test
    void distortionNoWorseThanTrivialFix() {
        // STC should never cost more than a trivial syndrome-satisfying embedding (flip one element
        // per unsatisfied parity). This sanity-checks that the optimizer is actually optimizing.
        Random r = new Random(555);
        for (int trial = 0; trial < 50; trial++) {
            int w = 2 + r.nextInt(6);
            int messageLen = 20 + r.nextInt(180);
            int n = messageLen * w;
            int[] x = new int[n];
            double[] rho = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = r.nextInt(2);
                rho[i] = 0.01 + r.nextDouble();
            }
            int[] message = new int[messageLen];
            for (int i = 0; i < messageLen; i++) {
                message[i] = r.nextInt(2);
            }
            int[] y = Stc.embed(x, rho, message, w, H);
            double stcCost = 0.0;
            int changes = 0;
            for (int i = 0; i < n; i++) {
                if (x[i] != y[i]) {
                    stcCost += rho[i];
                    changes++;
                }
            }
            // Trivial upper bound: changing one (average-cost) element per message bit
            double avgCost = 0.0;
            for (double c : rho) {
                avgCost += c;
            }
            avgCost /= n;
            double trivialBound = messageLen * avgCost;
            assertTrue(
                    stcCost <= trivialBound + 1e-9,
                    "STC distortion " + stcCost + " exceeds trivial bound " + trivialBound);
            assertTrue(changes <= n, "more changes than elements");
        }
    }

    @Test
    void chooseWidth() {
        assertEquals(4, Stc.chooseWidth(400, 100)); // 4 cover bits per message bit
        assertEquals(1, Stc.chooseWidth(100, 100)); // full payload
        assertEquals(0, Stc.chooseWidth(50, 100)); // message too big -> capacity exceeded
        assertEquals(1, Stc.chooseWidth(100, 0)); // empty message
    }
}
