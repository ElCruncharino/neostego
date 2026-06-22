/*
 * F5 Steganography
 * SPDX-License-Identifier: MIT
 *
 * F5 extraction algorithm. Recovers hidden data from quantized DCT coefficients.
 * Algorithm based on: Westfeld, "F5 - A Steganographic Algorithm" (2001).
 *
 * Adapted for NeoStego: the SSE original applied a deZigZag remap on the
 * permuted index here but NOT in the embedder, relying on the two paths being
 * fed arrays in different orders. We instead feed embed and extract the same
 * natural-order coefficient array (see F5Plugin.flattenCoeffs), so the remap is
 * dropped and both paths index coefficients identically. This keeps our own
 * embed/extract round-trip self-consistent; it is an intentional deviation from
 * the SSE bitstream's index mapping (the surrounding LSBDataHeader/crypto
 * container already differs, so cross-app steganogram interop is not a goal).
 */
package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.plugin.f5.crypto.F5Random;
import java.io.ByteArrayOutputStream;

/**
 * Extracts data hidden by the F5 steganographic algorithm from
 * quantized DCT coefficients.
 */
public final class F5Extract {

    /**
     * Extract hidden data from quantized DCT coefficients.
     *
     * @param coefficients flat array of quantized DCT coefficients (natural order per block)
     * @param coeffCount number of valid coefficients
     * @param key password/key bytes
     * @return extracted data bytes
     * @throws F5Exception if extraction fails
     */
    public static byte[] extract(int[] coefficients, int coeffCount, byte[] key) throws F5Exception {
        F5Random random = new F5Random(key);
        F5Permutation permutation = new F5Permutation(coeffCount, random);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int extractedByte = 0;
        int availableBits = 0;
        int extractedLength = 0;
        int bytesExtracted = 0;
        int shuffledIndex;
        int i;

        // Phase 1: Extract 32-bit length/status header
        for (i = 0; availableBits < 32; i++) {
            if (i >= coeffCount) {
                throw new F5Exception("Ran out of coefficients reading header (wrong key or no hidden data)");
            }
            shuffledIndex = permutation.getShuffled(i);

            // Skip DC coefficients
            if (shuffledIndex % 64 == 0) continue;

            // Get embeddable bit; skip zeros
            int bit = getEmbeddableBit(coefficients, shuffledIndex);
            if (bit < 0) continue; // zero coefficient

            extractedLength |= bit << availableBits++;
        }

        // Remove PRNG pad from length header
        // NOTE: getNextByte() returns sign-extended int (byte→int promotion).
        // The original F5 embedder uses the same sign-extended XOR, so we
        // must NOT mask with & 0xFF here — the sign bits are part of the pad.
        extractedLength ^= random.getNextByte();
        extractedLength ^= random.getNextByte() << 8;
        extractedLength ^= random.getNextByte() << 16;
        extractedLength ^= random.getNextByte() << 24;

        // Decode k parameter and actual length
        int k = (extractedLength >> 24) % 32;
        int n = (1 << k) - 1;
        extractedLength &= 0x007FFFFF;

        if (extractedLength <= 0 || extractedLength > 0x007FFFFF) {
            throw new F5Exception("Invalid extracted length: " + extractedLength + " (wrong key or no hidden data)");
        }

        availableBits = 0;
        extractedByte = 0;

        // Phase 2: Extract data
        if (n > 0) {
            // Matrix encoding: (1, n, k) code
            int startOfN = i;

            extractingLoop:
            while (true) {
                // Read n non-zero AC coefficients and compute k-bit hash
                int hash = 0;
                int code = 1;
                int j;
                for (j = 0; code <= n; j++) {
                    if (startOfN + j >= coeffCount) break extractingLoop;

                    shuffledIndex = permutation.getShuffled(startOfN + j);
                    if (shuffledIndex % 64 == 0) continue; // skip DC

                    int bit = getEmbeddableBit(coefficients, shuffledIndex);
                    if (bit < 0) continue; // skip zeros

                    if (bit == 1) hash ^= code;
                    code++;
                }
                startOfN += j;

                // Write k bits from hash
                for (int b = 0; b < k; b++) {
                    extractedByte |= ((hash >> b) & 1) << availableBits++;
                    if (availableBits == 8) {
                        // Remove PRNG pad (sign extension doesn't matter
                        // here since we only write the bottom 8 bits)
                        extractedByte ^= random.getNextByte();
                        output.write((byte) extractedByte);
                        extractedByte = 0;
                        availableBits = 0;
                        bytesExtracted++;
                        if (bytesExtracted == extractedLength) break extractingLoop;
                    }
                }
            }
        } else {
            // Default code (k=0 or k=1): one bit per non-zero coefficient
            for (; i < coeffCount; i++) {
                shuffledIndex = permutation.getShuffled(i);
                if (shuffledIndex % 64 == 0) continue;

                int bit = getEmbeddableBit(coefficients, shuffledIndex);
                if (bit < 0) continue;

                extractedByte |= bit << availableBits++;
                if (availableBits == 8) {
                    extractedByte ^= random.getNextByte();
                    output.write((byte) extractedByte);
                    extractedByte = 0;
                    availableBits = 0;
                    bytesExtracted++;
                    if (bytesExtracted == extractedLength) break;
                }
            }
        }

        if (bytesExtracted < extractedLength) {
            throw new F5Exception("Incomplete extraction: " + bytesExtracted + " of " + extractedLength + " bytes");
        }

        return output.toByteArray();
    }

    /**
     * Get the embeddable bit from a coefficient.
     * Returns -1 for zero coefficients (skip), 0 or 1 for the bit value.
     */
    private static int getEmbeddableBit(int[] coefficients, int index) {
        int coeff = coefficients[index];
        if (coeff == 0) return -1;
        if (coeff > 0) return coeff & 1;
        // negative: inverted LSB
        return 1 - (coeff & 1);
    }

    /** Exception for F5 extraction errors. */
    public static class F5Exception extends Exception {
        public F5Exception(String message) {
            super(message);
        }
    }
}
