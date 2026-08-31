/* !!! Doost !!! */

/*
   A Java implementation of BLAKE2B cryptographic digest algorithm.

   Joubin Mohammad Houshyar <alphazero@sensesay.net>
   bushwick, nyc
   02-14-2014

   --

   To the extent possible under law, the author(s) have dedicated all copyright
   and related and neighboring rights to this software to the public domain
   worldwide. This software is distributed without any warranty.

   You should have received a copy of the CC0 Public Domain Dedication along with
   this software. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
*/

package com.openstego.desktop.plugin.f5.crypto;

import static com.openstego.desktop.plugin.f5.crypto.Blake2bHash.Engine.LittleEndian.readInt;
import static com.openstego.desktop.plugin.f5.crypto.Blake2bHash.Engine.LittleEndian.readLong;

import java.security.spec.AlgorithmParameterSpec;

/**  */
public interface Blake2bHash {
    // ---------------------------------------------------------------------
    // Specification
    // ---------------------------------------------------------------------
    public interface Spec {
        /** pblock size of blake2b */
        int param_bytes = 64;

        /** pblock size of blake2b */
        int block_bytes = 128;

        /** maximum digest size */
        int max_digest_bytes = 64;

        /** maximum key sie */
        int max_key_bytes = 64;

        /** maximum salt size */
        int max_salt_bytes = 16;

        /** maximum personalization string size */
        int max_personalization_bytes = 16;

        /** length of h space vector array */
        int state_space_len = 8;

        /** max tree fanout value */
        int max_tree_fantout = 0xFF;

        /** max tree depth value */
        int max_tree_depth = 0xFF;

        /** max tree leaf length value.Note that this has uint32 semantics
         * and thus 0xFFFFFFFF is used as max value limit. */
        int max_tree_leaf_length = 0xFFFFFFFF;

        /** max node offset value. Note that this has uint64 semantics
         * and thus 0xFFFFFFFFFFFFFFFFL is used as max value limit. */
        long max_node_offset = 0xFFFFFFFFFFFFFFFFL;

        /** max tree inner length value */
        int max_tree_inner_length = 0xFF;

        /** initialization values map ref-Spec IV[i] -> slice iv[i*8:i*8+7] */
        long[] IV = {
            0x6a09e667f3bcc908L,
            0xbb67ae8584caa73bL,
            0x3c6ef372fe94f82bL,
            0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L,
            0x9b05688c2b3e6c1fL,
            0x1f83d9abfb41bd6bL,
            0x5be0cd19137e2179L
        };

        /** sigma per spec used in compress func generation - for reference only */
        static byte[][] sigma = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}
        };
    }

    // ---------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------
    // TODO add ByteBuffer variants

    /** */
    void update(byte[] input);

    /** */
    void update(byte input);

    /** */
    void update(byte[] input, int offset, int len);

    /** */
    byte[] doFinal();

    /** */
    byte[] doFinal(byte[] input);

    /** */
    int doFinal(byte[] output, int offset);

    /** */
    void doFinal(byte[] output, int offset, int len);

    /** */
    void reset();

    // ---------------------------------------------------------------------
    // Blake2b Message Digest
    // ---------------------------------------------------------------------

    /** Generalized Blake2b digest. */
    public static class Digest extends Engine implements Blake2bHash {
        private Digest() {
            super();
        }

        public static Digest newInstance() {
            return new Digest();
        }
    }

    // ---------------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------------
    static class Engine implements Blake2bHash {

        /* G0 sigmas */
        static final int[] sig_g00 = {
            0, 14, 11, 7, 9, 2, 12, 13, 6, 10, 0, 14,
        };
        static final int[] sig_g01 = {
            1, 10, 8, 9, 0, 12, 5, 11, 15, 2, 1, 10,
        };

        /* G1 sigmas */
        static final int[] sig_g10 = {
            2, 4, 12, 3, 5, 6, 1, 7, 14, 8, 2, 4,
        };
        static final int[] sig_g11 = {
            3, 8, 0, 1, 7, 10, 15, 14, 9, 4, 3, 8,
        };

        /* G2 sigmas */
        static final int[] sig_g20 = {
            4, 9, 5, 13, 2, 0, 14, 12, 11, 7, 4, 9,
        };
        static final int[] sig_g21 = {
            5, 15, 2, 12, 4, 11, 13, 1, 3, 6, 5, 15,
        };

        /* G3 sigmas */
        static final int[] sig_g30 = {
            6, 13, 15, 11, 10, 8, 4, 3, 0, 1, 6, 13,
        };
        static final int[] sig_g31 = {
            7, 6, 13, 14, 15, 3, 10, 9, 8, 5, 7, 6,
        };

        /* G4 sigmas */
        static final int[] sig_g40 = {
            8, 1, 10, 2, 14, 4, 0, 5, 12, 15, 8, 1,
        };
        static final int[] sig_g41 = {
            9, 12, 14, 6, 1, 13, 7, 0, 2, 11, 9, 12,
        };

        /* G5 sigmas */
        static final int[] sig_g50 = {
            10, 0, 3, 5, 11, 7, 6, 15, 13, 9, 10, 0,
        };
        static final int[] sig_g51 = {
            11, 2, 6, 10, 12, 5, 3, 4, 7, 14, 11, 2,
        };

        /* G6 sigmas */
        static final int[] sig_g60 = {
            12, 11, 7, 4, 6, 15, 9, 8, 1, 3, 12, 11,
        };
        static final int[] sig_g61 = {
            13, 7, 1, 0, 8, 14, 2, 6, 4, 12, 13, 7,
        };

        /* G7 sigmas */
        static final int[] sig_g70 = {
            14, 5, 9, 15, 3, 1, 8, 2, 10, 13, 14, 5,
        };
        static final int[] sig_g71 = {
            15, 3, 4, 8, 13, 9, 11, 10, 5, 0, 15, 3,
        };

        // ---------------------------------------------------------------------
        // Blake2b State(+) per reference implementation
        // ---------------------------------------------------------------------
        // REVU: address last_node TODO part of the Tree/incremental

        /** per spec */
        private final long[] h = new long[8];
        /** per spec */
        private final long[] t = new long[2];
        /** per spec */
        private final long[] f = new long[2];
        /** per spec (tree) */
        private boolean last_node = false;
        /** pulled up 2b optimal */
        private final long[] m = new long[16];
        /** pulled up 2b optimal */
        private final long[] v = new long[16];

        /** compressor cache buffer */
        private final byte[] buffer;
        /** compressor cache buffer offset/cached data length */
        private int buflen;

        /** configuration params */
        private final Param param;
        /** digest length from init param - copied here on init */
        private final int outlen;
        /** to support update(byte) */
        private byte[] oneByte;

        /** read only */
        private static byte[] zeropad = new byte[Spec.block_bytes];

        /** a little bit of semantics */
        interface flag {
            int last_block = 0;
            int last_node = 1;
        }

        // ---------------------------------------------------------------------
        // Ctor & Initialization
        // ---------------------------------------------------------------------

        /** Basic use constructor pending (TODO) JCA/JCE compliance */
        Engine() {
            this(new Param());
        }

        /** User provided Param for custom configurations */
        Engine(final Param param) {
            assert param != null : "param is null";
            this.param = param;
            this.buffer = new byte[Spec.block_bytes];
            this.oneByte = new byte[1];
            this.outlen = param.getDigestLength();

            if (param.getDepth() > Param.Default.depth) {
                final int ndepth = param.getNodeDepth();
                final long nxoff = param.getNodeOffset();
                if (ndepth == param.getDepth() - 1) {
                    last_node = true;
                    assert param.getNodeOffset() == 0 : "root must have offset of zero";
                } else if (param.getNodeOffset() == param.getFanout() - 1) {
                    this.last_node = true;
                }
            }

            initialize();

            //			Debug.dumpBuffer(System.out, "param bytes at init", param.getBytes());

        }

        private void initialize() {
            // state vector h - copy values to address reset() requests
            System.arraycopy(param.initialized_H(), 0, this.h, 0, Spec.state_space_len);

            //			Debug.dumpArray("init H", this.h);
            // if we have a key update initial block
            // Note param has zero padded key_bytes to Spec.max_key_bytes
            if (param.hasKey) {
                this.update(param.key_bytes, 0, Spec.block_bytes);
            }
        }

        // ---------------------------------------------------------------------
        // interface: Blake2b API
        // ---------------------------------------------------------------------

        /** {@inheritDoc} */
        @Override
        public final void reset() {
            // reset cache
            this.buflen = 0;
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) 0;
            }

            // reset flags
            this.f[0] = 0L;
            this.f[1] = 0L;

            // reset counters
            this.t[0] = 0L;
            this.t[1] = 0L;

            // reset state vector
            // NOTE: keep as last stmt as init calls update0 for MACs.
            initialize();
        }

        /** {@inheritDoc} */
        @Override
        public final void update(final byte[] b, int off, int len) {
            if (b == null) {
                throw new IllegalArgumentException("input buffer (b) is null");
            }
            /* zero or more calls to compress */
            // REVU: possibly the double buffering of c-ref is more sensible ..
            //       regardless, the hotspot is in the compress, as expected.
            while (len > 0) {
                if (buflen == 0) {
                    /* try compressing direct from input ? */
                    while (len > Spec.block_bytes) {
                        this.t[0] += Spec.block_bytes;
                        this.t[1] += this.t[0] == 0 ? 1 : 0;
                        compress(b, off);
                        len -= Spec.block_bytes;
                        off += Spec.block_bytes;
                    }
                } else if (buflen == Spec.block_bytes) {
                    /* flush */
                    this.t[0] += Spec.block_bytes;
                    this.t[1] += this.t[0] == 0 ? 1 : 0;
                    compress(buffer, 0);
                    buflen = 0;
                    continue;
                }

                // "are we there yet?"
                if (len == 0) return;

                final int cap = Spec.block_bytes - buflen;
                final int fill = len > cap ? cap : len;
                System.arraycopy(b, off, buffer, buflen, fill);
                buflen += fill;
                len -= fill;
                off += fill;
            }
        }

        /** {@inheritDoc} */
        @Override
        public final void update(byte b) {
            oneByte[0] = b;
            update(oneByte, 0, 1);
        }

        /** {@inheritDoc} */
        @Override
        public final void update(byte[] input) {
            update(input, 0, input.length);
        }

        /** {@inheritDoc} */
        @Override
        public final void doFinal(byte[] output, int off, int len) {
            // zero pad last block; set last block flags; and compress
            System.arraycopy(zeropad, 0, buffer, buflen, Spec.block_bytes - buflen);
            if (buflen > 0) {
                this.t[0] += buflen;
                this.t[1] += this.t[0] == 0 ? 1 : 0;
            }

            this.f[flag.last_block] = 0xFFFFFFFFFFFFFFFFL;
            this.f[flag.last_node] = this.last_node ? 0xFFFFFFFFFFFFFFFFL : 0x0L;

            // compres and write final out (truncated to len) to output
            compress(buffer, 0);
            hashout(output, off, len);

            reset();
        }

        public int doFinal(byte[] output, int off) throws IllegalArgumentException {

            doFinal(output, off, output.length);
            return param.getDigestLength();
        }

        /** {@inheritDoc} */
        @Override
        public final byte[] doFinal() throws IllegalArgumentException {
            final byte[] out = new byte[outlen];
            doFinal(out, 0, outlen);
            return out;
        }

        /** {@inheritDoc} */
        @Override
        public final byte[] doFinal(byte[] input) {
            update(input, 0, input.length);
            return doFinal();
        }

        public int getDigestSize() {
            return param.getDigestLength();
        }

        public String getAlgorithmName() {
            return "Blake2b";
        }

        public int getByteLength() {
            return Spec.block_bytes;
        }

        // ---------------------------------------------------------------------
        // Internal Ops
        // ---------------------------------------------------------------------

        /**
         * write out the digest output from the 'h' registers.
         * truncate full output if necessary.
         */
        private void hashout(final byte[] out, final int offset, final int hashlen) {
            // write max number of whole longs
            final int lcnt = hashlen >>> 3;
            long v = 0;
            int i = offset;
            for (int w = 0; w < lcnt; w++) {
                v = h[w];
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
                v >>>= 8;
                out[i++] = (byte) v;
            }

            // basta?
            if (hashlen == Spec.max_digest_bytes) return;

            // write the remaining bytes of a partial long value
            v = h[lcnt];
            i = lcnt << 3;
            while (i < hashlen) {
                out[offset + i] = (byte) v;
                v >>>= 8;
                ++i;
            }
        }

        ////////////////////////////////////////////////////////////////////////
        /// Compression Kernel /////////////////////////////////////////// BEGIN
        ////////////////////////////////////////////////////////////////////////

        /** compress Spec.block_bytes data from b, from offset */
        private void compress(final byte[] b, final int offset) {

            // set m registers
            // REVU: some small gains still possible here.
            m[0] = ((long) b[offset] & 0xFF);
            m[0] |= ((long) b[offset + 1] & 0xFF) << 8;
            m[0] |= ((long) b[offset + 2] & 0xFF) << 16;
            m[0] |= ((long) b[offset + 3] & 0xFF) << 24;
            m[0] |= ((long) b[offset + 4] & 0xFF) << 32;
            m[0] |= ((long) b[offset + 5] & 0xFF) << 40;
            m[0] |= ((long) b[offset + 6] & 0xFF) << 48;
            m[0] |= ((long) b[offset + 7]) << 56;

            m[1] = ((long) b[offset + 8] & 0xFF);
            m[1] |= ((long) b[offset + 9] & 0xFF) << 8;
            m[1] |= ((long) b[offset + 10] & 0xFF) << 16;
            m[1] |= ((long) b[offset + 11] & 0xFF) << 24;
            m[1] |= ((long) b[offset + 12] & 0xFF) << 32;
            m[1] |= ((long) b[offset + 13] & 0xFF) << 40;
            m[1] |= ((long) b[offset + 14] & 0xFF) << 48;
            m[1] |= ((long) b[offset + 15]) << 56;

            m[2] = ((long) b[offset + 16] & 0xFF);
            m[2] |= ((long) b[offset + 17] & 0xFF) << 8;
            m[2] |= ((long) b[offset + 18] & 0xFF) << 16;
            m[2] |= ((long) b[offset + 19] & 0xFF) << 24;
            m[2] |= ((long) b[offset + 20] & 0xFF) << 32;
            m[2] |= ((long) b[offset + 21] & 0xFF) << 40;
            m[2] |= ((long) b[offset + 22] & 0xFF) << 48;
            m[2] |= ((long) b[offset + 23]) << 56;

            m[3] = ((long) b[offset + 24] & 0xFF);
            m[3] |= ((long) b[offset + 25] & 0xFF) << 8;
            m[3] |= ((long) b[offset + 26] & 0xFF) << 16;
            m[3] |= ((long) b[offset + 27] & 0xFF) << 24;
            m[3] |= ((long) b[offset + 28] & 0xFF) << 32;
            m[3] |= ((long) b[offset + 29] & 0xFF) << 40;
            m[3] |= ((long) b[offset + 30] & 0xFF) << 48;
            m[3] |= ((long) b[offset + 31]) << 56;

            m[4] = ((long) b[offset + 32] & 0xFF);
            m[4] |= ((long) b[offset + 33] & 0xFF) << 8;
            m[4] |= ((long) b[offset + 34] & 0xFF) << 16;
            m[4] |= ((long) b[offset + 35] & 0xFF) << 24;
            m[4] |= ((long) b[offset + 36] & 0xFF) << 32;
            m[4] |= ((long) b[offset + 37] & 0xFF) << 40;
            m[4] |= ((long) b[offset + 38] & 0xFF) << 48;
            m[4] |= ((long) b[offset + 39]) << 56;

            m[5] = ((long) b[offset + 40] & 0xFF);
            m[5] |= ((long) b[offset + 41] & 0xFF) << 8;
            m[5] |= ((long) b[offset + 42] & 0xFF) << 16;
            m[5] |= ((long) b[offset + 43] & 0xFF) << 24;
            m[5] |= ((long) b[offset + 44] & 0xFF) << 32;
            m[5] |= ((long) b[offset + 45] & 0xFF) << 40;
            m[5] |= ((long) b[offset + 46] & 0xFF) << 48;
            m[5] |= ((long) b[offset + 47]) << 56;

            m[6] = ((long) b[offset + 48] & 0xFF);
            m[6] |= ((long) b[offset + 49] & 0xFF) << 8;
            m[6] |= ((long) b[offset + 50] & 0xFF) << 16;
            m[6] |= ((long) b[offset + 51] & 0xFF) << 24;
            m[6] |= ((long) b[offset + 52] & 0xFF) << 32;
            m[6] |= ((long) b[offset + 53] & 0xFF) << 40;
            m[6] |= ((long) b[offset + 54] & 0xFF) << 48;
            m[6] |= ((long) b[offset + 55]) << 56;

            m[7] = ((long) b[offset + 56] & 0xFF);
            m[7] |= ((long) b[offset + 57] & 0xFF) << 8;
            m[7] |= ((long) b[offset + 58] & 0xFF) << 16;
            m[7] |= ((long) b[offset + 59] & 0xFF) << 24;
            m[7] |= ((long) b[offset + 60] & 0xFF) << 32;
            m[7] |= ((long) b[offset + 61] & 0xFF) << 40;
            m[7] |= ((long) b[offset + 62] & 0xFF) << 48;
            m[7] |= ((long) b[offset + 63]) << 56;

            m[8] = ((long) b[offset + 64] & 0xFF);
            m[8] |= ((long) b[offset + 65] & 0xFF) << 8;
            m[8] |= ((long) b[offset + 66] & 0xFF) << 16;
            m[8] |= ((long) b[offset + 67] & 0xFF) << 24;
            m[8] |= ((long) b[offset + 68] & 0xFF) << 32;
            m[8] |= ((long) b[offset + 69] & 0xFF) << 40;
            m[8] |= ((long) b[offset + 70] & 0xFF) << 48;
            m[8] |= ((long) b[offset + 71]) << 56;

            m[9] = ((long) b[offset + 72] & 0xFF);
            m[9] |= ((long) b[offset + 73] & 0xFF) << 8;
            m[9] |= ((long) b[offset + 74] & 0xFF) << 16;
            m[9] |= ((long) b[offset + 75] & 0xFF) << 24;
            m[9] |= ((long) b[offset + 76] & 0xFF) << 32;
            m[9] |= ((long) b[offset + 77] & 0xFF) << 40;
            m[9] |= ((long) b[offset + 78] & 0xFF) << 48;
            m[9] |= ((long) b[offset + 79]) << 56;

            m[10] = ((long) b[offset + 80] & 0xFF);
            m[10] |= ((long) b[offset + 81] & 0xFF) << 8;
            m[10] |= ((long) b[offset + 82] & 0xFF) << 16;
            m[10] |= ((long) b[offset + 83] & 0xFF) << 24;
            m[10] |= ((long) b[offset + 84] & 0xFF) << 32;
            m[10] |= ((long) b[offset + 85] & 0xFF) << 40;
            m[10] |= ((long) b[offset + 86] & 0xFF) << 48;
            m[10] |= ((long) b[offset + 87]) << 56;

            m[11] = ((long) b[offset + 88] & 0xFF);
            m[11] |= ((long) b[offset + 89] & 0xFF) << 8;
            m[11] |= ((long) b[offset + 90] & 0xFF) << 16;
            m[11] |= ((long) b[offset + 91] & 0xFF) << 24;
            m[11] |= ((long) b[offset + 92] & 0xFF) << 32;
            m[11] |= ((long) b[offset + 93] & 0xFF) << 40;
            m[11] |= ((long) b[offset + 94] & 0xFF) << 48;
            m[11] |= ((long) b[offset + 95]) << 56;

            m[12] = ((long) b[offset + 96] & 0xFF);
            m[12] |= ((long) b[offset + 97] & 0xFF) << 8;
            m[12] |= ((long) b[offset + 98] & 0xFF) << 16;
            m[12] |= ((long) b[offset + 99] & 0xFF) << 24;
            m[12] |= ((long) b[offset + 100] & 0xFF) << 32;
            m[12] |= ((long) b[offset + 101] & 0xFF) << 40;
            m[12] |= ((long) b[offset + 102] & 0xFF) << 48;
            m[12] |= ((long) b[offset + 103]) << 56;

            m[13] = ((long) b[offset + 104] & 0xFF);
            m[13] |= ((long) b[offset + 105] & 0xFF) << 8;
            m[13] |= ((long) b[offset + 106] & 0xFF) << 16;
            m[13] |= ((long) b[offset + 107] & 0xFF) << 24;
            m[13] |= ((long) b[offset + 108] & 0xFF) << 32;
            m[13] |= ((long) b[offset + 109] & 0xFF) << 40;
            m[13] |= ((long) b[offset + 110] & 0xFF) << 48;
            m[13] |= ((long) b[offset + 111]) << 56;

            m[14] = ((long) b[offset + 112] & 0xFF);
            m[14] |= ((long) b[offset + 113] & 0xFF) << 8;
            m[14] |= ((long) b[offset + 114] & 0xFF) << 16;
            m[14] |= ((long) b[offset + 115] & 0xFF) << 24;
            m[14] |= ((long) b[offset + 116] & 0xFF) << 32;
            m[14] |= ((long) b[offset + 117] & 0xFF) << 40;
            m[14] |= ((long) b[offset + 118] & 0xFF) << 48;
            m[14] |= ((long) b[offset + 119]) << 56;

            m[15] = ((long) b[offset + 120] & 0xFF);
            m[15] |= ((long) b[offset + 121] & 0xFF) << 8;
            m[15] |= ((long) b[offset + 122] & 0xFF) << 16;
            m[15] |= ((long) b[offset + 123] & 0xFF) << 24;
            m[15] |= ((long) b[offset + 124] & 0xFF) << 32;
            m[15] |= ((long) b[offset + 125] & 0xFF) << 40;
            m[15] |= ((long) b[offset + 126] & 0xFF) << 48;
            m[15] |= ((long) b[offset + 127]) << 56;
            //			Debug.dumpArray("m @ compress", m);
            //
            //			Debug.dumpArray("h @ compress", h);
            //			Debug.dumpArray("t @ compress", t);
            //			Debug.dumpArray("f @ compress", f);

            // set v registers
            v[0] = h[0];
            v[1] = h[1];
            v[2] = h[2];
            v[3] = h[3];
            v[4] = h[4];
            v[5] = h[5];
            v[6] = h[6];
            v[7] = h[7];
            v[8] = 0x6a09e667f3bcc908L;
            v[9] = 0xbb67ae8584caa73bL;
            v[10] = 0x3c6ef372fe94f82bL;
            v[11] = 0xa54ff53a5f1d36f1L;
            v[12] = t[0] ^ 0x510e527fade682d1L;
            v[13] = t[1] ^ 0x9b05688c2b3e6c1fL;
            v[14] = f[0] ^ 0x1f83d9abfb41bd6bL;
            v[15] = f[1] ^ 0x5be0cd19137e2179L;

            //			Debug.dumpArray("v @ compress", v);
            // the rounds
            // REVU: let's try unrolling this again TODO do & bench
            for (int r = 0; r < 12; r++) {

                /**		G (r, 0, 0, 4,  8, 12); */
                v[0] = v[0] + v[4] + m[sig_g00[r]];
                v[12] ^= v[0];
                v[12] = (v[12] << 32) | (v[12] >>> 32);
                v[8] = v[8] + v[12];
                v[4] ^= v[8];
                v[4] = (v[4] >>> 24) | (v[4] << 40);
                v[0] = v[0] + v[4] + m[sig_g01[r]];
                v[12] ^= v[0];
                v[12] = (v[12] >>> 16) | (v[12] << 48);
                v[8] = v[8] + v[12];
                v[4] ^= v[8];
                v[4] = (v[4] << 1) | (v[4] >>> 63);

                /**		G (r, 1, 1, 5,  9, 13); */
                v[1] = v[1] + v[5] + m[sig_g10[r]];
                v[13] ^= v[1];
                v[13] = (v[13] << 32) | (v[13] >>> 32);
                v[9] = v[9] + v[13];
                v[5] ^= v[9];
                v[5] = (v[5] >>> 24) | (v[5] << 40);
                v[1] = v[1] + v[5] + m[sig_g11[r]];
                v[13] ^= v[1];
                v[13] = (v[13] >>> 16) | (v[13] << 48);
                v[9] = v[9] + v[13];
                v[5] ^= v[9];
                v[5] = (v[5] << 1) | (v[5] >>> 63);

                /**		G (r, 2, 2, 6, 10, 14); */
                v[2] = v[2] + v[6] + m[sig_g20[r]];
                v[14] ^= v[2];
                v[14] = (v[14] << 32) | (v[14] >>> 32);
                v[10] = v[10] + v[14];
                v[6] ^= v[10];
                v[6] = (v[6] >>> 24) | (v[6] << 40);
                v[2] = v[2] + v[6] + m[sig_g21[r]];
                v[14] ^= v[2];
                v[14] = (v[14] >>> 16) | (v[14] << 48);
                v[10] = v[10] + v[14];
                v[6] ^= v[10];
                v[6] = (v[6] << 1) | (v[6] >>> 63);

                /**		G (r, 3, 3, 7, 11, 15); */
                v[3] = v[3] + v[7] + m[sig_g30[r]];
                v[15] ^= v[3];
                v[15] = (v[15] << 32) | (v[15] >>> 32);
                v[11] = v[11] + v[15];
                v[7] ^= v[11];
                v[7] = (v[7] >>> 24) | (v[7] << 40);
                v[3] = v[3] + v[7] + m[sig_g31[r]];
                v[15] ^= v[3];
                v[15] = (v[15] >>> 16) | (v[15] << 48);
                v[11] = v[11] + v[15];
                v[7] ^= v[11];
                v[7] = (v[7] << 1) | (v[7] >>> 63);

                /**		G (r, 4, 0, 5, 10, 15); */
                v[0] = v[0] + v[5] + m[sig_g40[r]];
                v[15] ^= v[0];
                v[15] = (v[15] << 32) | (v[15] >>> 32);
                v[10] = v[10] + v[15];
                v[5] ^= v[10];
                v[5] = (v[5] >>> 24) | (v[5] << 40);
                v[0] = v[0] + v[5] + m[sig_g41[r]];
                v[15] ^= v[0];
                v[15] = (v[15] >>> 16) | (v[15] << 48);
                v[10] = v[10] + v[15];
                v[5] ^= v[10];
                v[5] = (v[5] << 1) | (v[5] >>> 63);

                /**		G (r, 5, 1, 6, 11, 12); */
                v[1] = v[1] + v[6] + m[sig_g50[r]];
                v[12] ^= v[1];
                v[12] = (v[12] << 32) | (v[12] >>> 32);
                v[11] = v[11] + v[12];
                v[6] ^= v[11];
                v[6] = (v[6] >>> 24) | (v[6] << 40);
                v[1] = v[1] + v[6] + +m[sig_g51[r]];
                v[12] ^= v[1];
                v[12] = (v[12] >>> 16) | (v[12] << 48);
                v[11] = v[11] + v[12];
                v[6] ^= v[11];
                v[6] = (v[6] << 1) | (v[6] >>> 63);

                /**		G (r, 6, 2, 7,  8, 13); */
                v[2] = v[2] + v[7] + m[sig_g60[r]];
                v[13] ^= v[2];
                v[13] = (v[13] << 32) | (v[13] >>> 32);
                v[8] = v[8] + v[13];
                v[7] ^= v[8];
                v[7] = (v[7] >>> 24) | (v[7] << 40);
                v[2] = v[2] + v[7] + m[sig_g61[r]];
                v[13] ^= v[2];
                v[13] = (v[13] >>> 16) | (v[13] << 48);
                v[8] = v[8] + v[13];
                v[7] ^= v[8];
                v[7] = (v[7] << 1) | (v[7] >>> 63);

                /**		G (r, 7, 3, 4,  9, 14); */
                v[3] = v[3] + v[4] + m[sig_g70[r]];
                v[14] ^= v[3];
                v[14] = (v[14] << 32) | (v[14] >>> 32);
                v[9] = v[9] + v[14];
                v[4] ^= v[9];
                v[4] = (v[4] >>> 24) | (v[4] << 40);
                v[3] = v[3] + v[4] + m[sig_g71[r]];
                v[14] ^= v[3];
                v[14] = (v[14] >>> 16) | (v[14] << 48);
                v[9] = v[9] + v[14];
                v[4] ^= v[9];
                v[4] = (v[4] << 1) | (v[4] >>> 63);
            }

            // Update state vector h
            h[0] ^= v[0] ^ v[8];
            h[1] ^= v[1] ^ v[9];
            h[2] ^= v[2] ^ v[10];
            h[3] ^= v[3] ^ v[11];
            h[4] ^= v[4] ^ v[12];
            h[5] ^= v[5] ^ v[13];
            h[6] ^= v[6] ^ v[14];
            h[7] ^= v[7] ^ v[15];

            //			Debug.dumpArray("v @ compress end", v);
            //			Debug.dumpArray("h @ compress end", h);
            /* kaamil */
        }

        ////////////////////////////////////////////////////////////////////////
        /// Compression Kernel //////////////////////////////////////////// FINI
        ////////////////////////////////////////////////////////////////////////

        // ---------------------------------------------------------------------
        // Little Endian Codecs (inlined in the compressor)
        /*
         * impl note: these are not library funcs and used in hot loops, so no
         * null or bounds checks are performed. For our purposes, this is OK.
         */
        // ---------------------------------------------------------------------

        public static class LittleEndian {
            public static int readInt(final byte[] b, int off) {
                int v0 = ((int) b[off++] & 0xFF);
                v0 |= ((int) b[off++] & 0xFF) << 8;
                v0 |= ((int) b[off++] & 0xFF) << 16;
                v0 |= ((int) b[off]) << 24;
                return v0;
            }
            /** Little endian - byte[] to long */
            public static long readLong(final byte[] b, int off) {
                long v0 = ((long) b[off++] & 0xFF);
                v0 |= ((long) b[off++] & 0xFF) << 8;
                v0 |= ((long) b[off++] & 0xFF) << 16;
                v0 |= ((long) b[off++] & 0xFF) << 24;
                v0 |= ((long) b[off++] & 0xFF) << 32;
                v0 |= ((long) b[off++] & 0xFF) << 40;
                v0 |= ((long) b[off++] & 0xFF) << 48;
                v0 |= ((long) b[off]) << 56;
                return v0;
            }
        }
    }
    // ---------------------------------------------------------------------
    // digest parameter (block)
    // ---------------------------------------------------------------------
    /** Blake2b configuration parameters block per spec */
    // REVU: need to review a revert back to non-lazy impl TODO: do & bench
    public static class Param implements AlgorithmParameterSpec {
        interface Xoff {
            int digest_length = 0;
            int key_length = 1;
            int fanout = 2;
            int depth = 3;
            int leaf_length = 4;
            int node_offset = 8;
            int node_depth = 16;
            int inner_length = 17;
            int reserved = 18;
            int salt = 32;
            int personal = 48;
        }

        public interface Default {
            byte digest_length = Spec.max_digest_bytes;
            byte key_length = 0;
            byte fanout = 1;
            byte depth = 1;
            int leaf_length = 0;
            long node_offset = 0;
            byte node_depth = 0;
            byte inner_length = 0;
        }
        /** default bytes of Blake2b parameter block */
        static final byte[] default_bytes = new byte[Spec.param_bytes];
        /** initialize default_bytes */
        static {
            default_bytes[Xoff.digest_length] = Default.digest_length;
            default_bytes[Xoff.key_length] = Default.key_length;
            default_bytes[Xoff.fanout] = Default.fanout;
            default_bytes[Xoff.depth] = Default.depth;
            /* def. leaf_length is 0 fill and already set by new byte[] */
            /* def. node_offset is 0 fill and already set by new byte[] */
            default_bytes[Xoff.node_depth] = Default.node_depth;
            default_bytes[Xoff.inner_length] = Default.inner_length;
            /* def. salt is 0 fill and already set by new byte[] */
            /* def. personal is 0 fill and already set by new byte[] */
        }
        /** default Blake2b h vector */
        static final long[] default_h = new long[Spec.state_space_len];

        static {
            default_h[0] = readLong(default_bytes, 0);
            default_h[1] = readLong(default_bytes, 8);
            default_h[2] = readLong(default_bytes, 16);
            default_h[3] = readLong(default_bytes, 24);
            default_h[4] = readLong(default_bytes, 32);
            default_h[5] = readLong(default_bytes, 40);
            default_h[6] = readLong(default_bytes, 48);
            default_h[7] = readLong(default_bytes, 56);

            default_h[0] ^= Spec.IV[0];
            default_h[1] ^= Spec.IV[1];
            default_h[2] ^= Spec.IV[2];
            default_h[3] ^= Spec.IV[3];
            default_h[4] ^= Spec.IV[4];
            default_h[5] ^= Spec.IV[5];
            default_h[6] ^= Spec.IV[6];
            default_h[7] ^= Spec.IV[7];
        }

        /** */
        private boolean hasKey = false;
        /** not sure how to make this secure - TODO */
        private byte[] key_bytes = null;
        /** */
        private byte[] bytes = null;
        /** */
        private final long[] h = new long[Spec.state_space_len];

        /** */
        public Param() {
            System.arraycopy(default_h, 0, h, 0, Spec.state_space_len);
        }
        /** */
        public long[] initialized_H() {
            return h;
        }
        final byte getByteParam(final int xoffset) {
            byte[] _bytes = bytes;
            if (_bytes == null) _bytes = Param.default_bytes;
            return _bytes[xoffset];
        }

        final int getIntParam(final int xoffset) {
            byte[] _bytes = bytes;
            if (_bytes == null) _bytes = Param.default_bytes;
            return readInt(_bytes, xoffset);
        }

        final long getLongParam(final int xoffset) {
            byte[] _bytes = bytes;
            if (_bytes == null) _bytes = Param.default_bytes;
            return readLong(_bytes, xoffset);
        }
        // TODO same for tree params depth, fanout, inner, node-depth, node-offset
        public final int getDigestLength() {
            return (int) getByteParam(Xoff.digest_length);
        }

        public final int getKeyLength() {
            return (int) getByteParam(Xoff.key_length);
        }

        public final int getFanout() {
            return (int) getByteParam(Xoff.fanout);
        }

        public final int getDepth() {
            return (int) getByteParam(Xoff.depth);
        }

        public final int getLeafLength() {
            return getIntParam(Xoff.leaf_length);
        }

        public final long getNodeOffset() {
            return getLongParam(Xoff.node_offset);
        }

        public final int getNodeDepth() {
            return (int) getByteParam(Xoff.node_depth);
        }

        public final int getInnerLength() {
            return (int) getByteParam(Xoff.inner_length);
        }

        public final boolean hasKey() {
            return this.hasKey;
        }

    }
}
