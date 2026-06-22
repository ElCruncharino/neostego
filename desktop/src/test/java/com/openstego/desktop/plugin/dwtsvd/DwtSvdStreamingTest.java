/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.util.dwt.DWT;
import com.openstego.desktop.util.dwt.Image;
import com.openstego.desktop.util.dwt.ImageTree;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Proves the streaming {@link DwtSvdTransform} is <em>bit-identical</em> to the legacy
 * {@link DWT}/{@code DWTUtil} path it replaces. This is the linchpin of the memory rewrite: as long as the
 * sub-band coefficients and the reconstructed pixels match coefficient-for-coefficient, a watermark embedded or
 * verified through the streaming path is fully interchangeable with the original, so all the robustness behaviour
 * (and existing watermarks) carry over unchanged. Tested across even/odd widths and heights, since the periodic
 * boundary wrap is exactly where a streaming reorganisation is most likely to diverge.
 */
public class DwtSvdStreamingTest {

    /** DWT-SVD's fixed configuration: biorthogonal filter #1, single level, periodical method. */
    private static final int FILTER_ID = 1;

    private static final int LEVEL = 1;

    private static final int METHOD = 2;

    private static final int[][] SIZES = {
        {64, 64}, {66, 48}, {65, 63}, {48, 65}, {100, 100}, {128, 96}, {37, 52}, {53, 37}, {256, 144}
    };

    @Test
    public void forwardBandsAreBitIdenticalToLegacyDwt() throws OpenStegoException {
        for (int[] size : SIZES) {
            int w = size[0];
            int h = size[1];
            int[][] rgb = randomRgb(w, h, 1234L + w * 31L + h);
            int[][] luminance = luminanceOf(rgb);

            ImageTree tree = new DWT(w, h, FILTER_ID, LEVEL, METHOD).forwardDWT(luminance);
            Image[] bands = new DwtSvdTransform(w, h).forward(rowSource(rgb), true);

            assertBandEquals(tree.getCoarse().getImage(), bands[0], "LL", w, h);
            assertBandEquals(tree.getHorizontal().getImage(), bands[1], "LH", w, h);
            assertBandEquals(tree.getVertical().getImage(), bands[2], "HL", w, h);
            assertBandEquals(tree.getDiagonal().getImage(), bands[3], "HH", w, h);
        }
    }

    @Test
    public void llOnlyForwardMatchesFullForward() throws OpenStegoException {
        for (int[] size : SIZES) {
            int w = size[0];
            int h = size[1];
            int[][] rgb = randomRgb(w, h, 99L + w * 7L + h);
            DwtSvdTransform t = new DwtSvdTransform(w, h);
            Image llFull = t.forward(rowSource(rgb), true)[0];
            Image llOnly = t.forward(rowSource(rgb), false)[0];
            assertBandEquals(llFull, llOnly, "LL(detail=false)", w, h);
        }
    }

    @Test
    public void inverseReconstructionIsBitIdenticalToLegacyDwt() throws OpenStegoException {
        for (int[] size : SIZES) {
            int w = size[0];
            int h = size[1];
            int[][] rgb = randomRgb(w, h, 4321L + w * 13L + h);
            int[][] luminance = luminanceOf(rgb);

            DWT dwt = new DWT(w, h, FILTER_ID, LEVEL, METHOD);
            ImageTree tree = dwt.forwardDWT(luminance);

            // Feed the streaming inverse the very same band data the legacy inverse will consume, so this isolates
            // the inverse transform. (A copy, because DWT.inverseDWT does not mutate the bands but we want to be sure
            // the two consumers cannot interfere.)
            Image[] bands = {
                copyOf(tree.getCoarse().getImage()),
                copyOf(tree.getHorizontal().getImage()),
                copyOf(tree.getVertical().getImage()),
                copyOf(tree.getDiagonal().getImage())
            };

            int[][] streamed = new int[h][w];
            new DwtSvdTransform(w, h).inverse(bands, (y, yr) -> System.arraycopy(yr, 0, streamed[y], 0, w));

            int[][] legacy = new int[h][w];
            dwt.inverseDWT(tree, legacy);

            for (int i = 0; i < h; i++) {
                for (int j = 0; j < w; j++) {
                    assertEquals(
                            legacy[i][j],
                            streamed[i][j],
                            "inverse Y mismatch at (" + i + "," + j + ") for " + w + "x" + h);
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private static void assertBandEquals(Image expected, Image actual, String name, int w, int h) {
        assertEquals(expected.getWidth(), actual.getWidth(), name + " width for " + w + "x" + h);
        assertEquals(expected.getHeight(), actual.getHeight(), name + " height for " + w + "x" + h);
        double[] e = expected.getData();
        double[] a = actual.getData();
        for (int k = 0; k < e.length; k++) {
            // Exact equality: the streaming path preserves the multiply-accumulate order, so coefficients must match
            // to the bit, not merely to a tolerance.
            assertEquals(e[k], a[k], 0.0, name + " coefficient " + k + " for " + w + "x" + h);
        }
    }

    private static Image copyOf(Image img) {
        Image out = new Image(img.getWidth(), img.getHeight());
        System.arraycopy(img.getData(), 0, out.getData(), 0, img.getData().length);
        return out;
    }

    private static int[][] randomRgb(int w, int h, long seed) {
        Random rnd = new Random(seed);
        int[][] rgb = new int[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                rgb[i][j] = (0xFF << 24) | (rnd.nextInt(0x1000000));
            }
        }
        return rgb;
    }

    private static int[][] luminanceOf(int[][] rgb) {
        int h = rgb.length;
        int w = rgb[0].length;
        int[][] y = new int[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                int p = rgb[i][j];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                y[i][j] = (int) ((0.299 * r) + (0.587 * g) + (0.114 * b));
            }
        }
        return y;
    }

    private static DwtSvdTransform.RowSource rowSource(int[][] rgb) {
        int h = rgb.length;
        int w = rgb[0].length;
        return new DwtSvdTransform.RowSource() {
            @Override
            public int getWidth() {
                return w;
            }

            @Override
            public int getHeight() {
                return h;
            }

            @Override
            public void readRow(int y, int[] rgbRow) {
                System.arraycopy(rgb[y], 0, rgbRow, 0, w);
            }
        };
    }
}
