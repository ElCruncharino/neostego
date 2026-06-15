/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image.jpeg;

import java.io.IOException;

/**
 * MSB-first bit reader over a JPEG entropy-coded segment. Removes byte stuffing (a {@code 0xFF} data
 * byte is followed by a stuffed {@code 0x00}) and supports restart-marker realignment. When the
 * stream is exhausted it pads with 1-bits, matching baseline JPEG decoders; the decode loop reads an
 * exact symbol count, so padding is only ever consumed past the meaningful data.
 */
final class BitReader {

    private final byte[] data;
    private int pos;
    private final int end;
    private int bitBuf;
    private int bitCnt;

    /**
     * @param data  full JPEG byte array
     * @param start offset of the first entropy byte (just after the SOS header)
     * @param end   offset just past the last entropy byte (the next marker)
     */
    BitReader(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    /** Fetches the next raw entropy byte, removing stuffing; returns -1 at a marker or end. */
    private int nextByte() {
        if (this.pos >= this.end) {
            return -1;
        }
        int b = this.data[this.pos++] & 0xFF;
        if (b == 0xFF) {
            int b2 = (this.pos < this.end) ? (this.data[this.pos] & 0xFF) : -1;
            if (b2 == 0x00) {
                this.pos++; // consume the stuffed zero, keep the 0xFF as data
            } else {
                this.pos--; // a marker (e.g. RST/EOI) starts here; leave it for restart()/caller
                return -1;
            }
        }
        return b;
    }

    /** @return the next bit (0 or 1), MSB first; 1 when padding past the segment. */
    int readBit() {
        if (this.bitCnt == 0) {
            int b = nextByte();
            if (b < 0) {
                return 1; // pad with 1-bits past valid data
            }
            this.bitBuf = b;
            this.bitCnt = 8;
        }
        this.bitCnt--;
        return (this.bitBuf >> this.bitCnt) & 1;
    }

    /** @return the next {@code n} bits as an unsigned integer, MSB first. */
    int readBits(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 1) | readBit();
        }
        return v;
    }

    /**
     * Realigns to a byte boundary and consumes the next restart marker (0xFF 0xD0..0xD7). Called by
     * the decode loop at each restart interval.
     *
     * @throws IOException if no restart marker is found where one is expected
     */
    void restart() throws IOException {
        this.bitCnt = 0;
        while (this.pos + 1 < this.end) {
            int b = this.data[this.pos] & 0xFF;
            int b2 = this.data[this.pos + 1] & 0xFF;
            if (b == 0xFF && b2 >= 0xD0 && b2 <= 0xD7) {
                this.pos += 2;
                return;
            }
            this.pos++;
        }
        throw new IOException("Corrupt JPEG: missing restart marker");
    }
}
