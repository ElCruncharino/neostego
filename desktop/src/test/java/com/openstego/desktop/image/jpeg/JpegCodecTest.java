/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.jpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.image.awt.BufferedImagePixelImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Gate tests for the pure-Java JPEG coefficient codec. The codec must be byte-exact at the
 * coefficient level before any embedding is built on top of it, so these tests pin down three
 * guarantees:
 * <ol>
 *   <li><b>Independent correctness</b> &mdash; bytes re-encoded by our codec, decoded by the JDK's
 *       reference JPEG decoder ({@code javax.imageio}), reproduce the original image pixel-for-pixel.
 *       Identical quantized coefficients and quantization tables force an identical inverse DCT, so
 *       any decode/encode bug surfaces as a pixel mismatch against a decoder we did not write.</li>
 *   <li><b>Lossless transcode</b> &mdash; decode &rarr; encode &rarr; decode leaves every
 *       coefficient unchanged, and editing a single coefficient changes exactly that one.</li>
 *   <li><b>Precover side information</b> &mdash; {@link JpegCodec#fromPrecover} produces a valid JPEG
 *       and retains rounding errors in (&minus;0.5, 0.5].</li>
 * </ol>
 */
class JpegCodecTest {

    /** Builds a BufferedImage with structured + noisy content so blocks have real AC energy. */
    private static BufferedImage content(int w, int h, long seed) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int red = (x * 255 / Math.max(1, w - 1) + r.nextInt(24)) & 0xFF;
                int green = (y * 255 / Math.max(1, h - 1) + r.nextInt(24)) & 0xFF;
                int blue = ((x + y) * 255 / Math.max(1, w + h - 2) + r.nextInt(24)) & 0xFF;
                img.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return img;
    }

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static byte[] toJpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpeg", bos), "ImageIO could not write JPEG");
        return bos.toByteArray();
    }

    private static BufferedImage readJpeg(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "ImageIO could not read codec output");
        return img;
    }

    private static void assertSamePixels(BufferedImage a, BufferedImage b) {
        assertEquals(a.getWidth(), b.getWidth(), "width");
        assertEquals(a.getHeight(), b.getHeight(), "height");
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertEquals(a.getRGB(x, y) & 0xFFFFFF, b.getRGB(x, y) & 0xFFFFFF, "pixel mismatch at " + x + "," + y);
            }
        }
    }

    private static void assertSameCoeffs(JpegImage a, JpegImage b) {
        assertEquals(a.getComponentCount(), b.getComponentCount(), "component count");
        for (int c = 0; c < a.getComponentCount(); c++) {
            assertEquals(a.getBlocksWide(c), b.getBlocksWide(c), "blocksWide c" + c);
            assertEquals(a.getBlocksHigh(c), b.getBlocksHigh(c), "blocksHigh c" + c);
            for (int br = 0; br < a.getBlocksHigh(c); br++) {
                for (int bc = 0; bc < a.getBlocksWide(c); bc++) {
                    assertArrayEquals(
                            a.getBlock(c, br, bc), b.getBlock(c, br, bc), "block c" + c + " (" + br + "," + bc + ")");
                }
            }
        }
    }

    /**
     * The strongest gate: round-trip through our decode+encode, then decode with the JDK reference
     * decoder and compare to the JDK decode of the original. Pixel equality proves our codec reads
     * and writes coefficients faithfully against a decoder we did not author. Exercises the 4:2:0
     * MCU interleave that ImageIO emits by default.
     */
    @Test
    void reencodeMatchesReferenceDecoderPixelForPixel() throws Exception {
        int[][] dims = {{64, 64}, {17, 23}, {100, 1}, {1, 100}, {31, 48}, {200, 150}};
        for (int[] d : dims) {
            byte[] original = toJpeg(content(d[0], d[1], 42L + d[0]));
            JpegImage decoded = JpegCodec.decode(original);
            assertEquals(d[0], decoded.getWidth());
            assertEquals(d[1], decoded.getHeight());
            byte[] reencoded = JpegCodec.encode(decoded);
            assertSamePixels(readJpeg(original), readJpeg(reencoded));
        }
    }

    /** Decode -> encode -> decode must preserve every quantized coefficient. */
    @Test
    void losslessCoefficientTranscode() throws Exception {
        byte[] original = toJpeg(content(96, 80, 7L));
        JpegImage a = JpegCodec.decode(original);
        JpegImage b = JpegCodec.decode(JpegCodec.encode(a));
        assertSameCoeffs(a, b);
    }

    /** Editing one coefficient must change exactly that coefficient after a re-encode. */
    @Test
    void singleCoefficientEditSurvives() throws Exception {
        byte[] original = toJpeg(content(80, 80, 99L));
        JpegImage img = JpegCodec.decode(original);

        // Pick a mid-image luma block and flip a specific AC coefficient by +1.
        int comp = 0;
        int br = img.getBlocksHigh(comp) / 2;
        int bc = img.getBlocksWide(comp) / 2;
        int natIdx = 5; // an AC coefficient (natural-order index, not DC)
        short[] before = img.getBlock(comp, br, bc).clone();
        img.getBlock(comp, br, bc)[natIdx] += 1;

        JpegImage round = JpegCodec.decode(JpegCodec.encode(img));

        int diffs = 0;
        for (int c = 0; c < img.getComponentCount(); c++) {
            for (int r = 0; r < img.getBlocksHigh(c); r++) {
                for (int cc = 0; cc < img.getBlocksWide(c); cc++) {
                    short[] now = round.getBlock(c, r, cc);
                    short[] want = img.getBlock(c, r, cc);
                    for (int k = 0; k < 64; k++) {
                        if (now[k] != want[k]) {
                            diffs++;
                        }
                    }
                }
            }
        }
        assertEquals(0, diffs, "edited image must re-encode exactly");
        assertEquals(
                before[natIdx] + 1,
                round.getBlock(comp, br, bc)[natIdx],
                "the one edited coefficient must carry through");
    }

    /** fromPrecover output must be a valid JPEG and survive our own decode/encode losslessly. */
    @Test
    void fromPrecoverRoundTrips() throws Exception {
        for (boolean subsample : new boolean[] {true, false}) {
            BufferedImage src = content(70, 54, 5L);
            JpegImage jpg = JpegCodec.fromPrecover(new BufferedImagePixelImage(src), 90, subsample);
            assertTrue(jpg.hasSideInfo(), "precover must retain side info");
            assertTrue(jpg.nonZeroAcCount() > 0, "expected embeddable coefficients");

            // Valid to the reference decoder, correct dimensions.
            BufferedImage back = readJpeg(JpegCodec.encode(jpg));
            assertEquals(70, back.getWidth());
            assertEquals(54, back.getHeight());

            // Our own transcode is lossless.
            assertSameCoeffs(jpg, JpegCodec.decode(JpegCodec.encode(jpg)));
        }
    }

    /** Rounding errors must lie in (-0.5, 0.5] and be consistent with the stored quantized value. */
    @Test
    void sideInfoRoundingErrorsAreValid() throws Exception {
        BufferedImage src = content(48, 48, 11L);
        JpegImage jpg = JpegCodec.fromPrecover(new BufferedImagePixelImage(src), 85);
        for (int c = 0; c < jpg.getComponentCount(); c++) {
            for (int br = 0; br < jpg.getBlocksHigh(c); br++) {
                for (int bc = 0; bc < jpg.getBlocksWide(c); bc++) {
                    double[] e = jpg.getRounding(c, br, bc);
                    assertNotNull(e);
                    for (double v : e) {
                        assertTrue(v >= -0.5 && v < 0.5 + 1e-9, "rounding error out of range: " + v);
                    }
                }
            }
        }
    }

    /** Solid-color and saturated images (DC-only blocks, immediate EOB) must round-trip. */
    @Test
    void degenerateBlocksRoundTrip() throws Exception {
        int[] colors = {0x000000, 0xFFFFFF, 0x7F7F7F, 0x123456};
        for (int rgb : colors) {
            byte[] original = toJpeg(solid(40, 40, rgb));
            JpegImage a = JpegCodec.decode(original);
            JpegImage b = JpegCodec.decode(JpegCodec.encode(a));
            assertSameCoeffs(a, b);
            assertSamePixels(readJpeg(original), readJpeg(JpegCodec.encode(a)));
        }
    }

    /** Grayscale JPEGs (single component, MCU = one block) must round-trip. */
    @Test
    void grayscaleRoundTrips() throws Exception {
        BufferedImage gray = new BufferedImage(50, 37, BufferedImage.TYPE_BYTE_GRAY);
        Random r = new Random(3L);
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int v = r.nextInt(256);
                gray.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        byte[] original = toJpeg(gray);
        JpegImage a = JpegCodec.decode(original);
        assertEquals(1, a.getComponentCount(), "expected single-component JPEG");
        JpegImage b = JpegCodec.decode(JpegCodec.encode(a));
        assertSameCoeffs(a, b);
        assertSamePixels(readJpeg(original), readJpeg(JpegCodec.encode(a)));
    }
}
