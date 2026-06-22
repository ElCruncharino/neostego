/*
 * F5 Steganography
 * SPDX-License-Identifier: MIT
 *
 * PRNG for F5: Blake2b-based DigestRandomGenerator.
 * Must produce byte-identical output to the original F5 implementation
 * for backward compatibility.
 */
package com.openstego.desktop.plugin.f5.crypto;

/**
 * Password-seeded PRNG for F5 steganography.
 * Uses Blake2b hash in a counter-based random generator.
 * Produces deterministic pseudo-random bytes from a password/key.
 */
public final class F5Random {

    private static final int BUFFER_CAPACITY = 512;

    private final DigestRng rng;
    private final byte[] buffer;
    private int bufferPos;

    public F5Random(byte[] key) {
        this.rng = new DigestRng(Blake2bHash.Digest.newInstance());
        this.rng.addSeedMaterial(key);
        this.buffer = new byte[BUFFER_CAPACITY];
        this.bufferPos = BUFFER_CAPACITY; // force initial fill
    }

    /**
     * Returns the next pseudo-random byte (as unsigned int 0-255).
     * NOTE: The original F5RandomPW returns a SIGNED byte cast to int,
     * so values can be negative (-128 to 127). We match that behavior
     * exactly for compatibility.
     */
    public int getNextByte() {
        if (bufferPos >= BUFFER_CAPACITY - 1) {
            rng.nextBytes(buffer);
            bufferPos = 0;
        } else {
            bufferPos++;
        }
        return buffer[bufferPos]; // signed byte, matches original
    }

    /**
     * Returns a pseudo-random value in [0, maxValue).
     * Combines 4 bytes into a 32-bit int, then mods by maxValue.
     */
    public int getNextValue(int maxValue) {
        int retVal = getNextByte() | (getNextByte() << 8) | (getNextByte() << 16) | (getNextByte() << 24);
        retVal %= maxValue;
        if (retVal < 0) retVal += maxValue;
        return retVal;
    }

    /**
     * Digest-based random generator.
     * Reimplementation of the Bouncy Castle DigestRandomGenerator pattern.
     * Algorithm is public/standard; this is a clean-room implementation
     * that produces identical output.
     */
    private static final class DigestRng {

        private static final long CYCLE_COUNT = 10;

        private final Blake2bHash.Digest digest;
        private final byte[] state;
        private final byte[] seed;
        private long stateCounter = 1;
        private long seedCounter = 1;

        DigestRng(Blake2bHash.Digest digest) {
            this.digest = digest;
            int digestSize = digest.getDigestSize();
            this.seed = new byte[digestSize];
            this.state = new byte[digestSize];
        }

        void addSeedMaterial(byte[] inSeed) {
            digestUpdate(inSeed);
            digestUpdate(seed);
            digestDoFinal(seed);
        }

        void nextBytes(byte[] bytes) {
            int stateOff = 0;
            generateState();
            for (int i = 0; i < bytes.length; i++) {
                if (stateOff == state.length) {
                    generateState();
                    stateOff = 0;
                }
                bytes[i] = state[stateOff++];
            }
        }

        private void generateState() {
            digestAddCounter(stateCounter++);
            digestUpdate(state);
            digestUpdate(seed);
            digestDoFinal(state);
            if ((stateCounter % CYCLE_COUNT) == 0) {
                cycleSeed();
            }
        }

        private void cycleSeed() {
            digestUpdate(seed);
            digestAddCounter(seedCounter++);
            digestDoFinal(seed);
        }

        private void digestAddCounter(long counterVal) {
            for (int i = 0; i < 8; i++) {
                digest.update((byte) counterVal);
                counterVal >>>= 8;
            }
        }

        private void digestUpdate(byte[] data) {
            digest.update(data, 0, data.length);
        }

        private void digestDoFinal(byte[] result) {
            digest.doFinal(result, 0);
        }
    }
}
