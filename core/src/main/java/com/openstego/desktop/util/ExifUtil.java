/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

/**
 * Dependency-free reader for the Exif orientation tag (TIFF tag 0x0112, values 1-8) embedded in JPEG
 * (Exif APP1 segment) or PNG ({@code eXIf} chunk) byte streams.
 *
 * <p>Both the desktop (ImageIO) and Android (BitmapFactory) decoders return raw stored pixels and never
 * apply this tag, so a cover photographed in portrait but stored landscape-with-orientation would be
 * embedded — and re-emitted — sideways relative to how every viewer renders it. Each client reads the
 * orientation here and rotates the decoded image upright before embedding (the pixel transform itself is
 * platform-specific: {@code BufferedImage} on desktop, {@code Bitmap}/{@code Matrix} on Android).
 */
public final class ExifUtil {

    private ExifUtil() {
    }

    /**
     * Read the Exif orientation tag from a JPEG or PNG byte stream.
     *
     * @param data raw image bytes
     * @return orientation 1-8 (1 = normal); 1 if absent or unparseable
     */
    public static int readExifOrientation(byte[] data) {
        try {
            if (data == null || data.length < 12) {
                return 1;
            }
            // JPEG: FF D8, then marker segments; orientation lives in the Exif APP1 (FF E1) segment.
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
                int pos = 2;
                while (pos + 4 <= data.length && (data[pos] & 0xFF) == 0xFF) {
                    int marker = data[pos + 1] & 0xFF;
                    if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                        pos += 2; // standalone markers carry no length
                        continue;
                    }
                    if (marker == 0xDA) {
                        break; // start of scan: image data follows, no more metadata
                    }
                    int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    if (length < 2 || pos + 2 + length > data.length) {
                        break;
                    }
                    int segStart = pos + 4;
                    int segEnd = pos + 2 + length;
                    // Exif APP1 payload begins with "Exif\0\0"; the TIFF block follows.
                    if (marker == 0xE1 && segEnd - segStart >= 6
                            && data[segStart] == 'E' && data[segStart + 1] == 'x'
                            && data[segStart + 2] == 'i' && data[segStart + 3] == 'f'
                            && data[segStart + 4] == 0 && data[segStart + 5] == 0) {
                        return parseTiffOrientation(data, segStart + 6, segEnd);
                    }
                    pos = segEnd;
                }
                return 1;
            }
            // PNG: 8-byte signature, then [len][type][data][crc] chunks; orientation in the eXIf chunk.
            if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
                int pos = 8;
                while (pos + 8 <= data.length) {
                    int len = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                            | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    if (len < 0 || pos + 12 + (long) len > data.length) {
                        break;
                    }
                    int dataStart = pos + 8;
                    if (data[pos + 4] == 'e' && data[pos + 5] == 'X' && data[pos + 6] == 'I' && data[pos + 7] == 'f') {
                        return parseTiffOrientation(data, dataStart, dataStart + len);
                    }
                    pos = dataStart + len + 4; // skip data + 4-byte CRC
                }
            }
        } catch (RuntimeException e) {
            return 1; // malformed metadata: fall back to no-op rather than failing the embed
        }
        return 1;
    }

    /** Parse TIFF tag 0x0112 (Orientation) from a TIFF/Exif block at {@code [off, end)}. */
    private static int parseTiffOrientation(byte[] d, int off, int end) {
        if (off + 8 > end) {
            return 1;
        }
        boolean little;
        if ((d[off] & 0xFF) == 0x49 && (d[off + 1] & 0xFF) == 0x49) {
            little = true;
        } else if ((d[off] & 0xFF) == 0x4D && (d[off + 1] & 0xFF) == 0x4D) {
            little = false;
        } else {
            return 1;
        }
        int ifdOff = off + readInt(d, off + 4, little);
        if (ifdOff + 2 > end || ifdOff < off) {
            return 1;
        }
        int count = readShort(d, ifdOff, little);
        int entry = ifdOff + 2;
        for (int i = 0; i < count && entry + 12 <= end; i++, entry += 12) {
            if (readShort(d, entry, little) == 0x0112) {
                int val = readShort(d, entry + 8, little); // SHORT value sits in the first 2 bytes of the value field
                return (val >= 1 && val <= 8) ? val : 1;
            }
        }
        return 1;
    }

    private static int readShort(byte[] d, int p, boolean little) {
        return little ? (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                : ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static int readInt(byte[] d, int p, boolean little) {
        return little
                ? (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24)
                : ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }
}
