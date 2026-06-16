/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform-neutral RGB&harr;YUV conversion operating on the {@link PixelImage} abstraction.
 * <p>
 * Watermarking plugins (e.g. DWT-SVD) embed in the luminance plane, so they need to split an image into Y/U/V
 * components and recombine them afterwards. The desktop build historically did this against AWT's
 * {@code BufferedImage}; expressing the same ITU-R BT.601 math against {@link PixelImage} lets the watermarking
 * plugins live in the core module and run unchanged on any platform that supplies an {@code ImageCodec}
 * (desktop AWT, Android Bitmap, etc.).
 */
public final class YuvImageUtil {

    private YuvImageUtil() {
    }

    /**
     * Split an image into Y, U, V and alpha planes.
     *
     * @param image Source image
     * @return List of four {@code int[height][width]} planes in order: Y (luminance), U, V, alpha
     */
    public static List<int[][]> getYuvFromImage(PixelImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int[][] y = new int[height][width];
        int[][] u = new int[height][width];
        int[][] v = new int[height][width];
        int[][] a = new int[height][width];

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = image.getRGB(j, i);
                int alpha = (rgb >> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                // Convert RGB to YUV colorspace (ITU-R BT.601 luma/chroma weighting)
                y[i][j] = (int) ((0.299 * r) + (0.587 * g) + (0.114 * b));
                u[i][j] = (int) ((-0.147 * r) - (0.289 * g) + (0.436 * b));
                v[i][j] = (int) ((0.615 * r) - (0.515 * g) - (0.100 * b));
                a[i][j] = alpha;
            }
        }

        List<int[][]> yuv = new ArrayList<>(4);
        yuv.add(y);
        yuv.add(u);
        yuv.add(v);
        yuv.add(a);
        return yuv;
    }

    /**
     * Write Y/U/V (and alpha) planes back into the target image as RGB. The conversion is the inverse of
     * {@link #getYuvFromImage(PixelImage)}. The full ARGB value is written; codecs that preserve the existing
     * alpha channel (e.g. Android Bitmap) end up with the original alpha because the target is the same image
     * the planes were read from.
     *
     * @param yuv    List of four planes (Y, U, V, alpha) as produced by {@link #getYuvFromImage(PixelImage)}
     * @param target Image to write the reconstructed pixels into (modified in place)
     */
    public static void applyYuvToImage(List<int[][]> yuv, PixelImage target) {
        int[][] y = yuv.get(0);
        int[][] u = yuv.get(1);
        int[][] v = yuv.get(2);
        int[][] a = yuv.get(3);

        int height = y.length;
        int width = y[0].length;

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                // Convert YUV back to RGB
                int r = pixelRange(y[i][j] + 1.140 * v[i][j]);
                int g = pixelRange(y[i][j] - 0.395 * u[i][j] - 0.581 * v[i][j]);
                int b = pixelRange(y[i][j] + 2.032 * u[i][j]);
                int alpha = a[i][j];

                target.setRGB(j, i, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    /**
     * Clamp a value to the valid 8-bit pixel range [0, 255].
     *
     * @param p Value
     * @return the value clamped to [0, 255]
     */
    public static int pixelRange(double p) {
        return (p > 255) ? 255 : (p < 0) ? 0 : (int) p;
    }
}
