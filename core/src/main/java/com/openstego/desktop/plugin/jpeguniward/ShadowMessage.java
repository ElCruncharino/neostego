/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStegoCrypto;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.util.ecc.ReedSolomon;
import java.util.Arrays;

/**
 * Plausible-deniability shadow message for {@link JpegUniwardPlugin}: a second, independently
 * password-keyed message hidden in the same JPEG as the primary one, with no way to tell from the
 * primary password (or a blind steganalysis pass) that it exists.
 * <p>
 * {@link #reservedIndices} is a <em>public</em>, password-independent function of band-0's carrier
 * count alone: every JpegUniward embed reserves this same fixed-size slice of band 0 and structurally
 * excludes it from the primary's own permutation (see {@code JpegUniwardPlugin#bandPermutation}'s
 * {@code exclude} parameter), whether or not a shadow message is actually written there. This is
 * deliberate, not an oversight: the reservation itself is a public property of the file format (visible
 * to anyone reading this code), so its presence never signals that a shadow is in use -- only
 * successfully decrypting it with the right password does. Structural exclusion (rather than costing the
 * reserved cells to discourage the primary optimizer, as an earlier version of this class did) is also
 * what makes this safe: a merely-expensive cell can still be the only way to satisfy the primary's
 * syndrome constraint in a fixed-width region like the bootstrap, silently corrupting whichever message
 * gets written there second. Excluding the cells from the candidate set entirely removes that risk.
 * <p>
 * Payload framing: {@code [1 byte encryptedLen][encryptedLen bytes AES-GCM ciphertext][zero padding to
 * RS_MESSAGE_BYTES]}, Reed-Solomon protected as one fixed block. The length prefix matters because
 * {@link OpenStegoCrypto#decrypt} derives its ciphertext length from the array it's given -- handing it
 * the zero-padded block directly would feed the padding into GCM tag verification and always fail.
 */
final class ShadowMessage {

    /** Plaintext budget; see the class javadoc for how this bounds against RS_MESSAGE_BYTES. */
    static final int PLAINTEXT_MAX = 48;

    private static final int RS_MESSAGE_BYTES = 128;
    private static final int RS_PARITY_BYTES = 32;
    private static final int RS_BLOCK_BYTES = RS_MESSAGE_BYTES + RS_PARITY_BYTES;

    /** Total band-0 carriers every JpegUniward embed reserves for a (possibly unused) shadow slot. */
    static final int RS_BLOCK_BITS = RS_BLOCK_BYTES * 8;

    /** Public, non-secret seed: the reservation must be computable without any password. */
    private static final char[] PUBLIC_SEED = "neostego-shadow-region-v1".toCharArray();

    /** Distinguishes this permutation from any real band's (see JpegUniwardPlugin#bandPermutation). */
    private static final int SEED_INDEX = -1;

    private ShadowMessage() {}

    /** RS-encoded, encrypted block ready to write bit-by-bit into the reserved carriers. */
    static byte[] encode(byte[] plaintext, char[] password) throws OpenStegoException {
        if (plaintext.length > PLAINTEXT_MAX) {
            throw new OpenStegoException(null, JpegUniwardPlugin.NAMESPACE, JpegUniwardErrors.ERR_SHADOW_TOO_LONG);
        }
        byte[] encrypted = new OpenStegoCrypto(password, OpenStegoCrypto.ALGO_AES128).encrypt(plaintext);
        if (encrypted.length > RS_MESSAGE_BYTES - 1) {
            throw new OpenStegoException(null, JpegUniwardPlugin.NAMESPACE, JpegUniwardErrors.ERR_SHADOW_TOO_LONG);
        }
        byte[] padded = new byte[RS_MESSAGE_BYTES];
        padded[0] = (byte) encrypted.length;
        System.arraycopy(encrypted, 0, padded, 1, encrypted.length);
        return new ReedSolomon(RS_PARITY_BYTES).encode(padded);
    }

    /** Reverses {@link #encode}; throws if the block isn't RS-correctable or the password is wrong. */
    static byte[] decode(byte[] block, char[] password) throws OpenStegoException {
        ReedSolomon rs = new ReedSolomon(RS_PARITY_BYTES);
        if (!rs.isCorrectable(block)) {
            throw new OpenStegoException(null, JpegUniwardPlugin.NAMESPACE, JpegUniwardErrors.ERR_SHADOW_NOT_FOUND);
        }
        byte[] padded = rs.decode(block);
        int encLen = padded[0] & 0xFF;
        if (encLen > RS_MESSAGE_BYTES - 1) {
            throw new OpenStegoException(null, JpegUniwardPlugin.NAMESPACE, JpegUniwardErrors.ERR_SHADOW_NOT_FOUND);
        }
        byte[] encrypted = Arrays.copyOfRange(padded, 1, 1 + encLen);
        try {
            return new OpenStegoCrypto(password, OpenStegoCrypto.ALGO_AES128).decrypt(encrypted);
        } catch (OpenStegoException ex) {
            throw new OpenStegoException(ex, JpegUniwardPlugin.NAMESPACE, JpegUniwardErrors.ERR_SHADOW_NOT_FOUND);
        }
    }

    /**
     * The band-0 enumeration indices every JpegUniward file reserves for the shadow slot, drawn from
     * {@code count} carriers by a fixed public permutation -- deterministic from band-0 geometry alone,
     * with no password of any kind, so both the primary embedder (to exclude them) and a shadow
     * extractor (to know where to look) derive the identical set independently.
     */
    static int[] reservedIndices(int count) throws OpenStegoException {
        return Arrays.copyOf(JpegUniwardPlugin.bandPermutation(count, PUBLIC_SEED, SEED_INDEX), RS_BLOCK_BITS);
    }
}
