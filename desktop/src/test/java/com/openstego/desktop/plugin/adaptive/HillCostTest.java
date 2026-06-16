/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HillCost}: dimensions are preserved, costs are strictly positive and finite, a
 * flat region costs far more than a textured one, and the busiest region of a real-ish image has the
 * lowest cost (i.e. the function steers changes toward texture).
 */
class HillCostTest {

    @Test
    void dimensionsAndPositivity() {
        Random r = new Random(1);
        int rows = 40;
        int cols = 50;
        int[][] img = new int[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                img[y][x] = r.nextInt(256);
            }
        }
        double[][] rho = HillCost.cost(img);
        assertEquals(rows, rho.length);
        assertEquals(cols, rho[0].length);
        for (double[] row : rho) {
            for (double v : row) {
                assertTrue(v > 0.0 && !Double.isInfinite(v) && !Double.isNaN(v), "cost must be finite and positive");
            }
        }
    }

    @Test
    void flatRegionCostsMoreThanTexture() {
        // Left half flat (128), right half random noise -> right half should be far cheaper.
        int rows = 64;
        int cols = 64;
        int[][] img = new int[rows][cols];
        Random r = new Random(42);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                img[y][x] = (x < cols / 2) ? 128 : r.nextInt(256);
            }
        }
        double[][] rho = HillCost.cost(img);

        double flatAvg = blockAverage(rho, 8, rows - 8, 8, cols / 2 - 8);
        double textAvg = blockAverage(rho, 8, rows - 8, cols / 2 + 8, cols - 8);
        assertTrue(flatAvg > textAvg * 10,
                "flat region (" + flatAvg + ") should cost far more than texture (" + textAvg + ")");
    }

    @Test
    void strongerTextureIsCheaper() {
        // Left half mild noise, right half strong noise (both non-flat, like real images). The more
        // textured half should have a lower average cost.
        int rows = 64;
        int cols = 64;
        int[][] img = new int[rows][cols];
        Random r = new Random(7);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                img[y][x] = (x < cols / 2)
                        ? 128 + r.nextInt(11) - 5       // mild texture (+-5)
                        : 128 + r.nextInt(201) - 100;   // strong texture (+-100)
            }
        }
        double[][] rho = HillCost.cost(img);
        double mildAvg = blockAverage(rho, 8, rows - 8, 8, cols / 2 - 8);
        double strongAvg = blockAverage(rho, 8, rows - 8, cols / 2 + 8, cols - 8);
        assertTrue(strongAvg < mildAvg,
                "stronger texture (" + strongAvg + ") should cost less than mild texture (" + mildAvg + ")");
    }

    private static double blockAverage(double[][] m, int y0, int y1, int x0, int x1) {
        double sum = 0.0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                sum += m[y][x];
                n++;
            }
        }
        return sum / n;
    }
}
