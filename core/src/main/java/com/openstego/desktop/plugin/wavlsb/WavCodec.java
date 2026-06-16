/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.wavlsb;

import com.openstego.desktop.OpenStegoException;

/**
 * Minimal RIFF/WAVE (PCM) parser used by {@link WavLSBPlugin}. It locates the {@code fmt } and {@code data}
 * chunks so the LSB embedder can hide bits in the least-significant byte of each PCM sample while leaving the
 * container (headers, other chunks) byte-for-byte intact. Only uncompressed integer PCM is supported - lossy
 * or compressed audio (MP3, AAC, Vorbis, ...) cannot carry LSB data, since re-encoding destroys it.
 */
final class WavCodec {

    /** Offset (into the file) of the first PCM sample byte. */
    final int dataOffset;
    /** Length in bytes of the PCM {@code data} chunk. */
    final int dataLength;
    /** Bytes per single-channel sample (bitsPerSample / 8). */
    final int bytesPerSample;
    /** Number of individual samples available as LSB carriers (one bit each). */
    final int sampleCount;

    private WavCodec(int dataOffset, int dataLength, int bytesPerSample) {
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
        this.bytesPerSample = bytesPerSample;
        this.sampleCount = dataLength / bytesPerSample;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }

    private static boolean tag(byte[] b, int i, String t) {
        if (i + 4 > b.length) {
            return false;
        }
        for (int k = 0; k < 4; k++) {
            if (b[i + k] != t.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a WAV byte array, returning the location of its PCM samples.
     *
     * @param wav WAV file bytes
     * @return parsed chunk locations
     * @throws OpenStegoException if the bytes are not an uncompressed PCM WAV
     */
    static WavCodec parse(byte[] wav) throws OpenStegoException {
        if (wav == null || wav.length < 44 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            throw new OpenStegoException(null, WavLSBPlugin.NAMESPACE, WavLSBErrors.INVALID_WAV_FILE);
        }

        int bitsPerSample = -1;
        int audioFormat = -1;
        int pos = 12; // first subchunk after "WAVE"
        while (pos + 8 <= wav.length) {
            long size = u32(wav, pos + 4);
            int body = pos + 8;
            if (tag(wav, pos, "fmt ") && body + 16 <= wav.length) {
                audioFormat = u16(wav, body);
                bitsPerSample = u16(wav, body + 14);
            } else if (tag(wav, pos, "data")) {
                if (bitsPerSample <= 0 || (bitsPerSample % 8) != 0) {
                    throw new OpenStegoException(null, WavLSBPlugin.NAMESPACE, WavLSBErrors.UNSUPPORTED_WAV_FORMAT);
                }
                // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (still integer PCM samples). Compressed formats differ.
                if (audioFormat != 1 && audioFormat != 0xFFFE) {
                    throw new OpenStegoException(null, WavLSBPlugin.NAMESPACE, WavLSBErrors.UNSUPPORTED_WAV_FORMAT);
                }
                int dataLen = (int) Math.min(size, (long) wav.length - body);
                return new WavCodec(body, dataLen, bitsPerSample / 8);
            }
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            pos = body + (int) size + ((size & 1L) == 1L ? 1 : 0);
        }
        throw new OpenStegoException(null, WavLSBPlugin.NAMESPACE, WavLSBErrors.INVALID_WAV_FILE);
    }

    /** @return maximum number of payload bytes (header included) that fit, one bit per sample. */
    int capacityBytes() {
        return this.sampleCount / 8;
    }

    /** File byte offset of the least-significant byte of sample {@code index} (little-endian PCM). */
    int sampleByteOffset(int index) {
        return this.dataOffset + index * this.bytesPerSample;
    }
}
