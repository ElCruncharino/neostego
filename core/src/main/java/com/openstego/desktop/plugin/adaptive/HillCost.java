/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

/**
 * HILL embedding-cost function, after Li, Wang, Huang &amp; Li, "A new cost function for spatial
 * image steganography" (IEEE ICIP, 2014).
 * <p>
 * For a single image channel the cost is
 * <pre>
 *   rho = ( |I * KB| * L1 )^-1 * L2
 * </pre>
 * where {@code KB} is the 3x3 high-pass filter {@code [[-1,2,-1],[2,-4,2],[-1,2,-1]]}, {@code L1} is
 * a 3x3 averaging filter and {@code L2} is a 15x15 averaging filter, {@code *} is 2-D convolution
 * with mirror-reflected borders, and the inversion is element-wise. Smooth/flat regions get a high
 * cost (changes there are easy to detect) while busy/textured regions get a low cost, so an
 * STC-coded embedding concentrates its changes where they hide best. This is used only by the
 * sender; the receiver never needs costs.
 */
public final class HillCost {

    private static final double EPS = 1e-10;

    private HillCost() {
        // Utility class
    }

    /**
     * Computes the HILL cost map for one image channel.
     *
     * @param img channel samples as {@code img[y][x]} in {@code 0..255}
     * @return cost map {@code rho[y][x]} (strictly positive), same dimensions as {@code img}
     */
    public static double[][] cost(int[][] img) {
        int rows = img.length;
        int cols = img[0].length;

        double[][] residual = highPassKB(img);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                residual[y][x] = Math.abs(residual[y][x]);
            }
        }
        double[][] smoothed = boxBlur(residual, 1);   // L1: 3x3 average
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                smoothed[y][x] = 1.0 / (smoothed[y][x] + EPS);
            }
        }
        return boxBlur(smoothed, 7);                  // L2: 15x15 average
    }

    /** Convolves a channel with the 3x3 KB high-pass filter using mirror-reflected borders. */
    private static double[][] highPassKB(int[][] img) {
        int rows = img.length;
        int cols = img[0].length;
        // KB = [[-1, 2, -1], [2, -4, 2], [-1, 2, -1]]
        int[][] k = {{-1, 2, -1}, {2, -4, 2}, {-1, 2, -1}};
        double[][] out = new double[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double acc = 0.0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = reflect(y + dy, rows);
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = reflect(x + dx, cols);
                        acc += k[dy + 1][dx + 1] * img[yy][xx];
                    }
                }
                out[y][x] = acc;
            }
        }
        return out;
    }

    /** Separable box blur (averaging filter) of window {@code 2*radius+1} with reflected borders. */
    private static double[][] boxBlur(double[][] in, int radius) {
        int rows = in.length;
        int cols = in[0].length;
        int window = 2 * radius + 1;
        double[][] tmp = new double[rows][cols];
        // Horizontal pass
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double acc = 0.0;
                for (int dx = -radius; dx <= radius; dx++) {
                    acc += in[y][reflect(x + dx, cols)];
                }
                tmp[y][x] = acc / window;
            }
        }
        double[][] out = new double[rows][cols];
        // Vertical pass
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double acc = 0.0;
                for (int dy = -radius; dy <= radius; dy++) {
                    acc += tmp[reflect(y + dy, rows)][x];
                }
                out[y][x] = acc / window;
            }
        }
        return out;
    }

    /** Reflects an index back into {@code [0, n)} (mirror padding, edge not repeated). */
    private static int reflect(int i, int n) {
        if (n == 1) {
            return 0;
        }
        while (i < 0 || i >= n) {
            if (i < 0) {
                i = -i;
            } else if (i >= n) {
                i = 2 * n - 2 - i;
            }
        }
        return i;
    }
}
