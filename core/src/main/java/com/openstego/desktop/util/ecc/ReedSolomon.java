/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util.ecc;

/**
 * Self-contained systematic Reed&ndash;Solomon error-correcting codec over GF(2^8) (primitive polynomial 0x11d,
 * generator &alpha; = 2). This is the same field used by QR codes and is well suited to protecting a small watermark
 * payload against the residual bit errors that survive the redundancy/voting stage of the watermarking pipeline.
 * <p>
 * A code with {@code nParity} parity bytes can correct up to {@code nParity / 2} byte errors at unknown positions. The
 * encoding is systematic, i.e. the first {@code k} bytes of the codeword are the original message and the trailing
 * {@code nParity} bytes are parity.
 * <p>
 * The implementation follows the classic syndrome / Berlekamp&ndash;Massey / Chien / Forney decoding pipeline.
 */
public final class ReedSolomon {

    private static final int PRIMITIVE = 0x11d;

    /** Antilog (exponent) table, doubled in length so that index addition never needs a modulo. */
    private static final int[] EXP = new int[512];

    /** Log table. */
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= PRIMITIVE;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    /** Number of parity bytes. */
    private final int nParity;

    /** Generator polynomial (length nParity + 1). */
    private final int[] generator;

    /**
     * Construct a codec with the given number of parity bytes.
     *
     * @param nParity Number of parity bytes (must be between 1 and 254)
     */
    public ReedSolomon(int nParity) {
        if (nParity < 1 || nParity > 254) {
            throw new IllegalArgumentException("nParity out of range: " + nParity);
        }
        this.nParity = nParity;
        this.generator = buildGenerator(nParity);
    }

    /**
     * @return number of parity bytes added by this codec
     */
    public int getParityLength() {
        return this.nParity;
    }

    // ------------------------------------------------------------------
    // Galois field helpers
    // ------------------------------------------------------------------

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return EXP[LOG[a] + LOG[b]];
    }

    private static int gfDiv(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("division by zero in GF(256)");
        }
        if (a == 0) {
            return 0;
        }
        return EXP[(LOG[a] - LOG[b] + 255) % 255];
    }

    private static int gfPow(int a, int power) {
        if (a == 0) {
            return 0;
        }
        int e = (LOG[a] * power) % 255;
        if (e < 0) {
            e += 255;
        }
        return EXP[e];
    }

    private static int gfInverse(int a) {
        return EXP[255 - LOG[a]];
    }

    private static int[] polyScale(int[] p, int x) {
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = gfMul(p[i], x);
        }
        return r;
    }

    private static int[] polyAdd(int[] p, int[] q) {
        int[] r = new int[Math.max(p.length, q.length)];
        for (int i = 0; i < p.length; i++) {
            r[i + r.length - p.length] = p[i];
        }
        for (int i = 0; i < q.length; i++) {
            r[i + r.length - q.length] ^= q[i];
        }
        return r;
    }

    private static int[] polyMul(int[] p, int[] q) {
        int[] r = new int[p.length + q.length - 1];
        for (int j = 0; j < q.length; j++) {
            for (int i = 0; i < p.length; i++) {
                r[i + j] ^= gfMul(p[i], q[j]);
            }
        }
        return r;
    }

    /** Evaluate polynomial {@code p} (highest degree first) at {@code x} using Horner's rule. */
    private static int polyEval(int[] p, int x) {
        int y = p[0];
        for (int i = 1; i < p.length; i++) {
            y = gfMul(y, x) ^ p[i];
        }
        return y;
    }

    private static int[] buildGenerator(int nsym) {
        int[] g = {1};
        for (int i = 0; i < nsym; i++) {
            g = polyMul(g, new int[]{1, EXP[i]});
        }
        return g;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * Systematically encode the given message, returning {@code data.length + nParity} bytes.
     *
     * @param data Message bytes (length must be at most 255 - nParity)
     * @return Codeword (data followed by parity)
     */
    public byte[] encode(byte[] data) {
        if (data.length + this.nParity > 255) {
            throw new IllegalArgumentException("message too long for RS(255): " + data.length);
        }
        int[] msg = new int[data.length + this.nParity];
        for (int i = 0; i < data.length; i++) {
            msg[i] = data[i] & 0xff;
        }
        for (int i = 0; i < data.length; i++) {
            int coef = msg[i];
            if (coef != 0) {
                for (int j = 1; j < this.generator.length; j++) {
                    msg[i + j] ^= gfMul(this.generator[j], coef);
                }
            }
        }
        // Restore the (untouched) message bytes; the tail now holds the parity.
        byte[] out = new byte[data.length + this.nParity];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < msg.length; i++) {
            out[i] = (byte) msg[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Decode a (possibly corrupted) codeword, correcting up to {@code nParity / 2} byte errors.
     *
     * @param code Received codeword of length {@code k + nParity}
     * @return The corrected {@code k} message bytes, or the raw (uncorrected) message bytes if the codeword carries
     * more errors than can be corrected. Callers that need to know whether correction succeeded should use
     * {@link #isCorrectable(byte[])}.
     */
    public byte[] decode(byte[] code) {
        int k = code.length - this.nParity;
        int[] msg = new int[code.length];
        for (int i = 0; i < code.length; i++) {
            msg[i] = code[i] & 0xff;
        }

        int[] synd = calcSyndromes(msg);
        if (isZero(synd)) {
            return extractMessage(code, k);
        }

        try {
            int[] errLoc = findErrorLocator(synd);
            int[] errPos = findErrors(reverse(errLoc), msg.length);
            if (errPos == null) {
                return extractMessage(code, k);
            }
            int[] corrected = correctErrata(msg, synd, errPos);
            // Verify: a successful correction yields all-zero syndromes.
            if (!isZero(calcSyndromes(corrected))) {
                return extractMessage(code, k);
            }
            byte[] out = new byte[k];
            for (int i = 0; i < k; i++) {
                out[i] = (byte) corrected[i];
            }
            return out;
        } catch (RuntimeException ex) {
            // Uncorrectable - degrade gracefully to the raw systematic message bytes.
            return extractMessage(code, k);
        }
    }

    /**
     * @param code Received codeword
     * @return true if the codeword is error-free or can be fully corrected
     */
    public boolean isCorrectable(byte[] code) {
        int[] msg = new int[code.length];
        for (int i = 0; i < code.length; i++) {
            msg[i] = code[i] & 0xff;
        }
        int[] synd = calcSyndromes(msg);
        if (isZero(synd)) {
            return true;
        }
        try {
            int[] errLoc = findErrorLocator(synd);
            int[] errPos = findErrors(reverse(errLoc), msg.length);
            if (errPos == null) {
                return false;
            }
            int[] corrected = correctErrata(msg, synd, errPos);
            return isZero(calcSyndromes(corrected));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private byte[] extractMessage(byte[] code, int k) {
        byte[] out = new byte[k];
        System.arraycopy(code, 0, out, 0, k);
        return out;
    }

    /** Syndromes, padded with a leading zero (Forney convention). */
    private int[] calcSyndromes(int[] msg) {
        int[] synd = new int[this.nParity + 1];
        synd[0] = 0;
        for (int i = 0; i < this.nParity; i++) {
            synd[i + 1] = polyEval(msg, EXP[i]);
        }
        return synd;
    }

    private static boolean isZero(int[] synd) {
        for (int v : synd) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    private int[] findErrorLocator(int[] synd) {
        int[] errLoc = {1};
        int[] oldLoc = {1};
        for (int i = 0; i < this.nParity; i++) {
            int delta = synd[i + 1];
            for (int j = 1; j < errLoc.length; j++) {
                delta ^= gfMul(errLoc[errLoc.length - 1 - j], synd[i + 1 - j]);
            }
            oldLoc = append(oldLoc, 0);
            if (delta != 0) {
                if (oldLoc.length > errLoc.length) {
                    int[] newLoc = polyScale(oldLoc, delta);
                    oldLoc = polyScale(errLoc, gfInverse(delta));
                    errLoc = newLoc;
                }
                errLoc = polyAdd(errLoc, polyScale(oldLoc, delta));
            }
        }
        errLoc = stripLeadingZeros(errLoc);
        return errLoc;
    }

    private int[] findErrors(int[] errLoc, int nmess) {
        int errs = errLoc.length - 1;
        int[] pos = new int[errs];
        int count = 0;
        for (int i = 0; i < nmess; i++) {
            if (polyEval(errLoc, gfPow(2, i)) == 0) {
                if (count < errs) {
                    pos[count] = nmess - 1 - i;
                }
                count++;
            }
        }
        if (count != errs) {
            return null; // too many (or too few) errors to locate
        }
        return pos;
    }

    private int[] correctErrata(int[] msg, int[] synd, int[] errPos) {
        // Coefficient positions (from the left/high-order end).
        int[] coefPos = new int[errPos.length];
        for (int i = 0; i < errPos.length; i++) {
            coefPos[i] = msg.length - 1 - errPos[i];
        }

        int[] errLoc = errataLocator(coefPos);
        // Error evaluator polynomial Omega(x), highest-degree-first.
        int[] errEval = errorEvaluator(reverse(synd), errLoc, errLoc.length - 1);

        // X[i] = alpha^(coefPos[i]) is the error locator value for each error position.
        int[] x = new int[coefPos.length];
        for (int i = 0; i < coefPos.length; i++) {
            x[i] = EXP[coefPos[i] % 255];
        }

        int[] result = msg.clone();
        // Forney algorithm to compute error magnitudes.
        for (int i = 0; i < x.length; i++) {
            int xi = x[i];
            int xiInv = gfInverse(xi);

            // Formal derivative of the error locator, evaluated via the product form.
            int errLocPrime = 1;
            for (int j = 0; j < x.length; j++) {
                if (j != i) {
                    errLocPrime = gfMul(errLocPrime, 1 ^ gfMul(xiInv, x[j]));
                }
            }
            if (errLocPrime == 0) {
                throw new ArithmeticException("could not find error magnitude");
            }

            int y = polyEval(errEval, xiInv);
            y = gfMul(xi, y);

            int magnitude = gfDiv(y, errLocPrime);
            result[errPos[i]] ^= magnitude;
        }
        return result;
    }

    private static int[] errataLocator(int[] coefPos) {
        int[] eLoc = {1};
        for (int p : coefPos) {
            eLoc = polyMul(eLoc, polyAdd(new int[]{1}, new int[]{EXP[p], 0}));
        }
        return eLoc;
    }

    private int[] errorEvaluator(int[] synd, int[] errLoc, int nsym) {
        int[] mul = polyMul(synd, errLoc);
        int len = mul.length;
        int[] remainder = new int[nsym + 1];
        System.arraycopy(mul, len - (nsym + 1), remainder, 0, nsym + 1);
        return remainder;
    }

    // ------------------------------------------------------------------
    // Small array helpers
    // ------------------------------------------------------------------

    private static int[] append(int[] a, int v) {
        int[] r = new int[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = v;
        return r;
    }

    private static int[] reverse(int[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[a.length - 1 - i];
        }
        return r;
    }

    private static int[] stripLeadingZeros(int[] a) {
        int idx = 0;
        while (idx < a.length - 1 && a[idx] == 0) {
            idx++;
        }
        if (idx == 0) {
            return a;
        }
        int[] r = new int[a.length - idx];
        System.arraycopy(a, idx, r, 0, r.length);
        return r;
    }
}
