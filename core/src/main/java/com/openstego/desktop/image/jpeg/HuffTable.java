/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.jpeg;

/**
 * A single baseline JPEG Huffman table, built from a {@code (BITS, HUFFVAL)} specification as in
 * ITU-T T.81 Annex C. Supports both directions:
 * <ul>
 *   <li>decoding &mdash; the canonical {@code MINCODE/MAXCODE/VALPTR} structure (Annex F.2.2.3) used
 *       to map an incoming bit string to a symbol; this works for the arbitrary tables found in a
 *       source stream's DHT segments;</li>
 *   <li>encoding &mdash; per-symbol code and length arrays (Annex C.2) used when writing with the
 *       standard tables.</li>
 * </ul>
 */
final class HuffTable {

    /** BITS: number of codes of each length 1..16 (index 0 unused). */
    private final int[] bits = new int[17];
    /** HUFFVAL: symbols, in increasing code order. */
    private final int[] huffVal;

    // Decode structure (Annex F.2.2.3).
    private final int[] minCode = new int[17];
    private final int[] maxCode = new int[17];
    private final int[] valPtr = new int[17];

    // Encode structure (Annex C.2): code and size per symbol value (0..255).
    private final int[] eHufCo = new int[256];
    private final int[] eHufSi = new int[256];

    /**
     * @param bitsSpec    counts of codes per length; either 16 entries (lengths 1..16) or 17 with a
     *                    leading unused slot
     * @param huffValSpec symbol values in code order
     */
    HuffTable(int[] bitsSpec, int[] huffValSpec) {
        if (bitsSpec.length == 16) {
            System.arraycopy(bitsSpec, 0, this.bits, 1, 16);
        } else {
            System.arraycopy(bitsSpec, 0, this.bits, 0, 17);
        }
        this.huffVal = huffValSpec.clone();
        build();
    }

    private void build() {
        // HUFFSIZE / HUFFCODE generation (Annex C.2, Figures C.1 - C.2).
        int total = 0;
        for (int l = 1; l <= 16; l++) {
            total += this.bits[l];
        }
        int[] huffSize = new int[total + 1];
        int k = 0;
        for (int l = 1; l <= 16; l++) {
            for (int i = 0; i < this.bits[l]; i++) {
                huffSize[k++] = l;
            }
        }
        huffSize[k] = 0;

        int[] huffCode = new int[total];
        int code = 0;
        int si = huffSize[0];
        k = 0;
        while (huffSize[k] != 0) {
            while (huffSize[k] == si) {
                huffCode[k] = code;
                code++;
                k++;
            }
            code <<= 1;
            si++;
        }

        // Encode tables: code + size indexed by symbol value.
        for (int i = 0; i < total; i++) {
            int sym = this.huffVal[i];
            this.eHufCo[sym] = huffCode[i];
            this.eHufSi[sym] = huffSize[i];
        }

        // Decode tables: MINCODE/MAXCODE/VALPTR per length (Annex F.2.2.3, Figure F.15).
        int p = 0;
        for (int l = 1; l <= 16; l++) {
            if (this.bits[l] > 0) {
                this.valPtr[l] = p;
                this.minCode[l] = huffCode[p];
                p += this.bits[l];
                this.maxCode[l] = huffCode[p - 1];
            } else {
                this.maxCode[l] = -1;
            }
        }
    }

    /** @return the code length (number of bits) for a symbol, for the encoder. */
    int sizeOf(int symbol) {
        return this.eHufSi[symbol];
    }

    /** @return the Huffman code for a symbol, for the encoder. */
    int codeOf(int symbol) {
        return this.eHufCo[symbol];
    }

    /**
     * Decodes the next symbol from the bit reader using this table (Annex F.2.2.3, Figure F.16).
     *
     * @param in bit reader positioned at the start of a Huffman-coded symbol
     * @return the decoded symbol value
     * @throws java.io.IOException if the bit string matches no code in the table or the stream ends
     */
    int decode(BitReader in) throws java.io.IOException {
        int code = in.readBit();
        int l = 1;
        while (l <= 16 && (this.maxCode[l] < 0 || code > this.maxCode[l])) {
            code = (code << 1) | in.readBit();
            l++;
        }
        if (l > 16) {
            throw new java.io.IOException("Corrupt JPEG: bad Huffman code");
        }
        return this.huffVal[this.valPtr[l] + (code - this.minCode[l])];
    }
}
