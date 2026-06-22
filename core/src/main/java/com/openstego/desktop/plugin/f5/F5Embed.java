/*
 * F5 Steganography
 * SPDX-License-Identifier: MIT
 *
 * F5 embedding algorithm. Hides data in quantized DCT coefficients.
 * Uses matrix encoding (1,n,k) codes to minimize coefficient changes.
 * Algorithm: Westfeld, "F5 - A Steganographic Algorithm" (2001).
 */
package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.plugin.f5.crypto.F5Random;

public final class F5Embed {

    public static final class EmbedResult {
        public final int changed, thrown, embedded, k, n;

        EmbedResult(int c, int t, int e, int k, int n) {
            changed = c;
            thrown = t;
            embedded = e;
            this.k = k;
            this.n = n;
        }
    }

    /**
     * Embed data into coefficient array (modified in-place).
     * @param coeff      flat DCT coefficient array
     * @param coeffCount number of coefficients
     * @param data       payload bytes to embed
     * @param key        password bytes
     * @return statistics
     */
    public static EmbedResult embed(int[] coeff, int coeffCount, byte[] data, byte[] key) throws F5Extract.F5Exception {

        F5Random random = new F5Random(key);
        F5Permutation perm = new F5Permutation(coeffCount, random);

        // --- Capacity estimation ---
        int ones = 0, zeros = 0;
        for (int x = 0; x < coeffCount; x++) {
            if (x % 64 == 0) continue;
            if (coeff[x] == 0) zeros++;
            else if (coeff[x] == 1 || coeff[x] == -1) ones++;
        }
        int large = coeffCount - zeros - ones - coeffCount / 64;
        int expected = large + (int) (0.49 * ones);

        // --- Choose (1,n,k) code ---
        int dataLen = Math.min(data.length, 0x007FFFFF);
        int i;
        for (i = 1; i < 8; i++) {
            int ni = (1 << i) - 1;
            int usable = expected * i / ni - expected * i / ni % ni;
            usable /= 8;
            if (usable == 0 || usable < dataLen + 4) break;
        }
        int k = i - 1;
        int n = (1 << k) - 1;
        if (n == 0) n = 1;

        // --- Build & pad 32-bit header ---
        int byteToEmbed = (k << 24) | (dataLen & 0x007FFFFF);
        byteToEmbed ^= random.getNextByte();
        byteToEmbed ^= random.getNextByte() << 8;
        byteToEmbed ^= random.getNextByte() << 16;
        byteToEmbed ^= random.getNextByte() << 24;

        int nextBit = byteToEmbed & 1;
        byteToEmbed >>= 1;
        int availBits = 31;
        int changed = 0, thrown = 0, embedded = 1;
        int si; // shuffled index

        if (n > 1) {
            // ====== MATRIX ENCODING ======

            // Phase 1: embed header bit-by-bit
            for (i = 0; i < coeffCount; i++) {
                si = perm.getShuffled(i);
                if (si % 64 == 0 || coeff[si] == 0) continue;

                if (coeff[si] > 0) {
                    if ((coeff[si] & 1) != nextBit) {
                        coeff[si]--;
                        changed++;
                    }
                } else {
                    if ((coeff[si] & 1) == nextBit) {
                        coeff[si]++;
                        changed++;
                    }
                }

                if (coeff[si] != 0) {
                    if (availBits == 0) break;
                    nextBit = byteToEmbed & 1;
                    byteToEmbed >>= 1;
                    availBits--;
                    embedded++;
                } else {
                    thrown++;
                }
            }

            // Phase 2: embed data with (1,n,k) code
            int startOfN = i + 1;
            int dataPos = 0;
            availBits = 0;
            boolean lastByte = false;
            int[] cw = new int[n]; // codeword indices
            int hash, extractedBit, endOfN;

            embeddingLoop:
            do {
                // Collect k bits to embed
                int kBits = 0;
                for (int b = 0; b < k; b++) {
                    if (availBits == 0) {
                        if (dataPos >= dataLen) {
                            lastByte = true;
                            break;
                        }
                        byteToEmbed = (data[dataPos++] & 0xFF);
                        byteToEmbed ^= random.getNextByte();
                        availBits = 8;
                    }
                    nextBit = byteToEmbed & 1;
                    byteToEmbed >>= 1;
                    availBits--;
                    kBits |= nextBit << b;
                    embedded++;
                }

                // Embed k bits using matrix code (with shrinkage retry)
                do {
                    int j = startOfN;
                    for (int ci = 0; ci < n; j++) {
                        if (j >= coeffCount) break embeddingLoop;
                        si = perm.getShuffled(j);
                        if (si % 64 == 0 || coeff[si] == 0) continue;
                        cw[ci++] = si;
                    }
                    endOfN = j;

                    hash = 0;
                    for (int ci = 0; ci < n; ci++) {
                        if (coeff[cw[ci]] > 0) extractedBit = coeff[cw[ci]] & 1;
                        else extractedBit = 1 - (coeff[cw[ci]] & 1);
                        if (extractedBit == 1) hash ^= ci + 1;
                    }

                    i = hash ^ kBits;
                    if (i == 0) break; // already correct
                    i--;
                    if (coeff[cw[i]] > 0) coeff[cw[i]]--;
                    else coeff[cw[i]]++;
                    changed++;
                    if (coeff[cw[i]] == 0) thrown++;
                } while (coeff[cw[i]] == 0); // shrinkage: retry

                startOfN = endOfN;
            } while (!lastByte);

        } else {
            // ====== DEFAULT CODE: 1 bit per coefficient ======
            int dataPos = 0;

            for (i = 0; i < coeffCount; i++) {
                si = perm.getShuffled(i);
                if (si % 64 == 0 || coeff[si] == 0) continue;

                if (coeff[si] > 0) {
                    if ((coeff[si] & 1) != nextBit) {
                        coeff[si]--;
                        changed++;
                    }
                } else {
                    if ((coeff[si] & 1) == nextBit) {
                        coeff[si]++;
                        changed++;
                    }
                }

                if (coeff[si] != 0) {
                    embedded++;
                    if (availBits == 0) {
                        if (dataPos >= dataLen) break;
                        byteToEmbed = (data[dataPos++] & 0xFF);
                        byteToEmbed ^= random.getNextByte();
                        availBits = 8;
                    }
                    nextBit = byteToEmbed & 1;
                    byteToEmbed >>= 1;
                    availBits--;
                } else {
                    thrown++;
                }
            }
        }

        return new EmbedResult(changed, thrown, embedded, k, n);
    }
}
