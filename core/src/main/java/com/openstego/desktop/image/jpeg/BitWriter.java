/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.jpeg;

import java.io.ByteArrayOutputStream;

/**
 * MSB-first bit writer for a JPEG entropy-coded segment. Applies byte stuffing (a {@code 0xFF} data
 * byte is followed by a {@code 0x00}) and pads the final partial byte with 1-bits, per ITU-T T.81.
 * The encoder writes no restart markers (restart interval 0).
 */
final class BitWriter {

    private final ByteArrayOutputStream out;
    private int bitBuf;
    private int bitCnt;

    BitWriter(ByteArrayOutputStream out) {
        this.out = out;
    }

    private void putBit(int bit) {
        this.bitBuf = (this.bitBuf << 1) | (bit & 1);
        this.bitCnt++;
        if (this.bitCnt == 8) {
            emit(this.bitBuf & 0xFF);
            this.bitBuf = 0;
            this.bitCnt = 0;
        }
    }

    private void emit(int b) {
        this.out.write(b);
        if (b == 0xFF) {
            this.out.write(0); // byte stuffing
        }
    }

    /**
     * Writes the low {@code size} bits of {@code code}, MSB first.
     *
     * @param code  bit pattern (Huffman code or magnitude bits)
     * @param size  number of bits to write
     */
    void writeBits(int code, int size) {
        for (int i = size - 1; i >= 0; i--) {
            putBit((code >> i) & 1);
        }
    }

    /** Pads any partial byte with 1-bits to a byte boundary (called once at end of scan). */
    void pad() {
        while (this.bitCnt != 0) {
            putBit(1);
        }
    }
}
