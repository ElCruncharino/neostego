/*
 * F5 Steganography
 * SPDX-License-Identifier: MIT
 *
 * Fisher-Yates permutation, identical to original F5 for backward compatibility.
 */
package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.plugin.f5.crypto.F5Random;

/**
 * Generates a permutation of indices [0..size-1] using Fisher-Yates shuffle.
 * The shuffle order is determined by the F5Random PRNG seeded with a password.
 * This must produce an identical permutation to the original F5 implementation.
 */
public final class F5Permutation {

    private final int[] shuffled;

    /**
     * Creates a shuffled permutation of [0..size-1].
     * Uses reverse Fisher-Yates: picks random elements from the unshuffled
     * portion and swaps them to the end.
     */
    public F5Permutation(int size, F5Random random) {
        shuffled = new int[size];
        for (int i = 0; i < size; i++) {
            shuffled[i] = i;
        }
        int maxRandom = size;
        for (int i = 0; i < size; i++) {
            int randomIndex = random.getNextValue(maxRandom--);
            int tmp = shuffled[randomIndex];
            shuffled[randomIndex] = shuffled[maxRandom];
            shuffled[maxRandom] = tmp;
        }
    }

    /** Returns the permuted index at position i. */
    public int getShuffled(int i) {
        return shuffled[i];
    }

    /** Returns the total size of the permutation. */
    public int size() {
        return shuffled.length;
    }
}
