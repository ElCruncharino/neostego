/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the Exif/eXIf orientation handling that {@link ImageUtil} adds on top of ImageIO (which ignores
 * the tag). Guards against the "stego output is rotated relative to the cover" regression.
 */
class ExifOrientationTest {

    /** A 26-byte little-endian TIFF/Exif block carrying a single Orientation (0x0112) tag. */
    private static byte[] tiffWithOrientation(int value) {
        return new byte[] {
                'I', 'I',                          // little-endian
                0x2A, 0x00,                        // magic 42
                0x08, 0x00, 0x00, 0x00,            // IFD0 offset = 8
                0x01, 0x00,                        // entry count = 1
                0x12, 0x01,                        // tag 0x0112 (Orientation)
                0x03, 0x00,                        // type SHORT
                0x01, 0x00, 0x00, 0x00,            // count = 1
                (byte) value, 0x00, 0x00, 0x00,    // value (SHORT in first 2 bytes)
                0x00, 0x00, 0x00, 0x00             // next IFD = 0
        };
    }

    private static byte[] jpegWithOrientation(int value) throws Exception {
        byte[] tiff = tiffWithOrientation(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                       // SOI
        out.write(0xFF); out.write(0xE1);                       // APP1
        int segLen = 2 + 6 + tiff.length;                       // length field + "Exif\0\0" + TIFF
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF);
        out.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.write(tiff);
        out.write(0xFF); out.write(0xD9);                       // EOI
        return out.toByteArray();
    }

    private static byte[] pngWithOrientation(int value) throws Exception {
        byte[] tiff = tiffWithOrientation(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}); // signature
        int len = tiff.length;
        out.write((len >> 24) & 0xFF); out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        out.write(new byte[] {'e', 'X', 'I', 'f'});
        out.write(tiff);
        out.write(new byte[] {0, 0, 0, 0});                    // CRC (not validated by the reader)
        return out.toByteArray();
    }

    @Test
    void readsOrientationFromJpegApp1() throws Exception {
        for (int o = 1; o <= 8; o++) {
            assertEquals(o, ExifUtil.readExifOrientation(jpegWithOrientation(o)), "jpeg orientation " + o);
        }
    }

    @Test
    void readsOrientationFromPngExifChunk() throws Exception {
        assertEquals(6, ExifUtil.readExifOrientation(pngWithOrientation(6)));
    }

    @Test
    void defaultsToOneWhenAbsentOrMalformed() {
        assertEquals(1, ExifUtil.readExifOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}));
        assertEquals(1, ExifUtil.readExifOrientation(new byte[] {1, 2, 3}));
        assertEquals(1, ExifUtil.readExifOrientation(null));
    }

    @Test
    void rotate90CwSwapsDimsAndMovesCorner() {
        // 2x3 source; mark the top-left pixel. Orientation 6 = rotate 90 CW.
        BufferedImage src = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        int red = 0xFFFF0000;
        src.setRGB(0, 0, red);
        BufferedImage dst = ImageUtil.applyExifOrientation(src, 6);

        assertEquals(3, dst.getWidth(), "width and height swap for a 90° rotation");
        assertEquals(2, dst.getHeight());
        // src(0,0) -> dst(h-1-0, 0) = dst(2, 0)
        assertEquals(red, dst.getRGB(2, 0));
        assertEquals(BufferedImage.TYPE_INT_ARGB, dst.getType());
    }

    @Test
    void orientationOneIsAnArgbCopy() {
        BufferedImage src = new BufferedImage(4, 5, BufferedImage.TYPE_INT_RGB);
        src.setRGB(1, 2, 0xFF00FF00);
        BufferedImage dst = ImageUtil.applyExifOrientation(src, 1);
        assertEquals(4, dst.getWidth());
        assertEquals(5, dst.getHeight());
        assertEquals(0xFF00FF00, dst.getRGB(1, 2));
    }
}
