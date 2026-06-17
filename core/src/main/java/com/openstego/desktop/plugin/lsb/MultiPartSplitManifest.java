/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Metadata block that {@link MultiCoverPayloadSplitter} prepends to each cover's payload when a
 * single file is split across multiple cover images (upstream issue #67).
 * <p>
 * The manifest lives <em>inside</em> the bytes that the LSB plugin embeds, so each stego image
 * remains an ordinary {@link LSBDataHeader} (version 2) container and the format of single-image
 * stego files is left completely unchanged. The whole payload is compressed/encrypted <em>once</em>
 * by the splitter before being divided, so the per-image LSB header records no compression or
 * encryption; the manifest carries the real flags (and the encryption algorithm) needed to reverse
 * those steps after the parts are reassembled.
 * <p>
 * On-image layout of a part's payload is {@code [manifest][chunk]}, where the manifest is:
 * <pre>
 *   off size field
 *   0   2    MAGIC "MS"
 *   2   1    FORMAT_VERSION (currently 1)
 *   3   4    sessionId            (int, big-endian) - identifies parts of the same split
 *   7   2    partIndex            (unsigned short, big-endian) - 0-based
 *   9   2    totalParts           (unsigned short, big-endian)
 *   11  4    totalLength          (int, big-endian) - length of the whole processed blob
 *   15  4    chunkLength          (int, big-endian) - bytes of this part's chunk
 *   19  1    flags                (bit0 = compression, bit1 = encryption)
 *   20  8    cryptAlgo            (UTF-8, null-padded) - encryption algorithm name
 *   28  1    fileNameLen          (unsigned)
 *   29  ..   fileName             (UTF-8) - carried in part 0 only
 * </pre>
 */
public class MultiPartSplitManifest {

    /**
     * Magic bytes at the start of the manifest, used to tell a split part from an ordinary payload
     */
    public static final byte[] MAGIC = {(byte) 'M', (byte) 'S'};

    /**
     * Manifest format version
     */
    public static final byte FORMAT_VERSION = (byte) 1;

    /**
     * Length of the encryption-algorithm field, in bytes (matches {@link LSBDataHeader})
     */
    private static final int CRYPT_ALGO_LENGTH = 8;

    /**
     * Size of the fixed (filename-independent) portion of the manifest, in bytes
     */
    public static final int FIXED_SIZE = MAGIC.length + 1 + 4 + 2 + 2 + 4 + 4 + 1 + CRYPT_ALGO_LENGTH + 1;

    private static final int FLAG_COMPRESSION = 0x01;
    private static final int FLAG_ENCRYPTION = 0x02;

    private final int sessionId;
    private final int partIndex;
    private final int totalParts;
    private final int totalLength;
    private final int chunkLength;
    private final boolean useCompression;
    private final boolean useEncryption;
    private final String encryptionAlgorithm;
    private final byte[] fileNameBytes;

    /**
     * Constructor used when writing (embedding) a part.
     *
     * @param sessionId           Identifier shared by all parts of the same split
     * @param partIndex           0-based index of this part
     * @param totalParts          Total number of parts in the split
     * @param totalLength         Length of the whole processed (compressed/encrypted) blob
     * @param chunkLength         Number of payload bytes carried by this part
     * @param useCompression      Whether the whole blob was compressed
     * @param useEncryption       Whether the whole blob was encrypted
     * @param encryptionAlgorithm Encryption algorithm name (only meaningful when encrypted)
     * @param fileName            Original file name; should be supplied for part 0 only, may be null
     */
    public MultiPartSplitManifest(
            int sessionId,
            int partIndex,
            int totalParts,
            int totalLength,
            int chunkLength,
            boolean useCompression,
            boolean useEncryption,
            String encryptionAlgorithm,
            String fileName) {
        this.sessionId = sessionId;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
        this.totalLength = totalLength;
        this.chunkLength = chunkLength;
        this.useCompression = useCompression;
        this.useEncryption = useEncryption;
        this.encryptionAlgorithm = encryptionAlgorithm;
        this.fileNameBytes = (fileName == null) ? new byte[0] : fileName.getBytes(StandardCharsets.UTF_8);
    }

    private MultiPartSplitManifest(
            int sessionId,
            int partIndex,
            int totalParts,
            int totalLength,
            int chunkLength,
            boolean useCompression,
            boolean useEncryption,
            String encryptionAlgorithm,
            byte[] fileNameBytes) {
        this.sessionId = sessionId;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
        this.totalLength = totalLength;
        this.chunkLength = chunkLength;
        this.useCompression = useCompression;
        this.useEncryption = useEncryption;
        this.encryptionAlgorithm = encryptionAlgorithm;
        this.fileNameBytes = fileNameBytes;
    }

    /**
     * Serializes this manifest to its on-image byte form.
     *
     * @return Manifest bytes (length is {@link #size()})
     */
    public byte[] toBytes() {
        byte[] out = new byte[size()];
        int idx = 0;

        System.arraycopy(MAGIC, 0, out, idx, MAGIC.length);
        idx += MAGIC.length;
        out[idx++] = FORMAT_VERSION;
        idx = putInt(out, idx, this.sessionId);
        idx = putShort(out, idx, this.partIndex);
        idx = putShort(out, idx, this.totalParts);
        idx = putInt(out, idx, this.totalLength);
        idx = putInt(out, idx, this.chunkLength);

        int flags = 0;
        if (this.useCompression) {
            flags |= FLAG_COMPRESSION;
        }
        if (this.useEncryption) {
            flags |= FLAG_ENCRYPTION;
        }
        out[idx++] = (byte) flags;

        if (this.encryptionAlgorithm != null) {
            byte[] algo = this.encryptionAlgorithm.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(algo, 0, out, idx, Math.min(algo.length, CRYPT_ALGO_LENGTH));
        }
        idx += CRYPT_ALGO_LENGTH;

        out[idx++] = (byte) this.fileNameBytes.length;
        System.arraycopy(this.fileNameBytes, 0, out, idx, this.fileNameBytes.length);

        return out;
    }

    /**
     * Parses the manifest from the front of a part's payload bytes.
     *
     * @param payload The bytes extracted from a single cover (manifest followed by the chunk)
     * @return Parsed manifest
     * @throws OpenStegoException If the bytes do not begin with a valid manifest
     */
    public static MultiPartSplitManifest parse(byte[] payload) throws OpenStegoException {
        if (payload == null || payload.length < FIXED_SIZE) {
            throw corrupt("payload too short for a split manifest");
        }
        if (payload[0] != MAGIC[0] || payload[1] != MAGIC[1]) {
            throw corrupt("missing split-manifest marker (not a multi-cover part)");
        }
        if (payload[2] != FORMAT_VERSION) {
            throw corrupt("unsupported manifest version: " + (payload[2] & 0xFF));
        }

        int idx = 3;
        int sessionId = getInt(payload, idx);
        idx += 4;
        int partIndex = getShort(payload, idx);
        idx += 2;
        int totalParts = getShort(payload, idx);
        idx += 2;
        int totalLength = getInt(payload, idx);
        idx += 4;
        int chunkLength = getInt(payload, idx);
        idx += 4;
        int flags = payload[idx++] & 0xFF;
        boolean useCompression = (flags & FLAG_COMPRESSION) != 0;
        boolean useEncryption = (flags & FLAG_ENCRYPTION) != 0;
        String algo = new String(payload, idx, CRYPT_ALGO_LENGTH, StandardCharsets.UTF_8).trim();
        idx += CRYPT_ALGO_LENGTH;
        int fileNameLen = payload[idx++] & 0xFF;

        if (payload.length < FIXED_SIZE + fileNameLen) {
            throw corrupt("payload too short for declared file name");
        }
        byte[] fileNameBytes = Arrays.copyOfRange(payload, idx, idx + fileNameLen);

        if (totalParts < 1 || partIndex < 0 || partIndex >= totalParts) {
            throw corrupt("invalid part index " + partIndex + " of " + totalParts);
        }
        if (chunkLength < 0 || totalLength < 0) {
            throw corrupt("negative length in manifest");
        }
        if (payload.length < FIXED_SIZE + fileNameLen + chunkLength) {
            throw corrupt("payload shorter than declared chunk length");
        }

        return new MultiPartSplitManifest(
                sessionId,
                partIndex,
                totalParts,
                totalLength,
                chunkLength,
                useCompression,
                useEncryption,
                algo,
                fileNameBytes);
    }

    /**
     * Returns this part's chunk bytes from a payload that was parsed by {@link #parse(byte[])}.
     *
     * @param payload The same payload bytes that were passed to {@link #parse(byte[])}
     * @return The chunk bytes belonging to this part
     */
    public byte[] getChunk(byte[] payload) {
        int start = size();
        return Arrays.copyOfRange(payload, start, start + this.chunkLength);
    }

    /**
     * @return Total size of this manifest in bytes, including the file name
     */
    public int size() {
        return FIXED_SIZE + this.fileNameBytes.length;
    }

    /**
     * @return Session identifier shared by all parts of the same split
     */
    public int getSessionId() {
        return this.sessionId;
    }

    /**
     * @return 0-based index of this part
     */
    public int getPartIndex() {
        return this.partIndex;
    }

    /**
     * @return Total number of parts in the split
     */
    public int getTotalParts() {
        return this.totalParts;
    }

    /**
     * @return Length of the whole processed (compressed/encrypted) blob
     */
    public int getTotalLength() {
        return this.totalLength;
    }

    /**
     * @return Number of payload bytes carried by this part
     */
    public int getChunkLength() {
        return this.chunkLength;
    }

    /**
     * @return Whether the whole blob was compressed
     */
    public boolean isUseCompression() {
        return this.useCompression;
    }

    /**
     * @return Whether the whole blob was encrypted
     */
    public boolean isUseEncryption() {
        return this.useEncryption;
    }

    /**
     * @return Encryption algorithm name (only meaningful when encrypted)
     */
    public String getEncryptionAlgorithm() {
        return this.encryptionAlgorithm;
    }

    /**
     * @return Original file name (empty string if none was embedded)
     */
    public String getFileName() {
        return new String(this.fileNameBytes, StandardCharsets.UTF_8);
    }

    private static OpenStegoException corrupt(String detail) {
        return new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_CORRUPT, detail);
    }

    private static int putInt(byte[] out, int idx, int value) {
        out[idx] = (byte) ((value >> 24) & 0xFF);
        out[idx + 1] = (byte) ((value >> 16) & 0xFF);
        out[idx + 2] = (byte) ((value >> 8) & 0xFF);
        out[idx + 3] = (byte) (value & 0xFF);
        return idx + 4;
    }

    private static int putShort(byte[] out, int idx, int value) {
        out[idx] = (byte) ((value >> 8) & 0xFF);
        out[idx + 1] = (byte) (value & 0xFF);
        return idx + 2;
    }

    private static int getInt(byte[] in, int idx) {
        return ((in[idx] & 0xFF) << 24)
                | ((in[idx + 1] & 0xFF) << 16)
                | ((in[idx + 2] & 0xFF) << 8)
                | (in[idx + 3] & 0xFF);
    }

    private static int getShort(byte[] in, int idx) {
        return ((in[idx] & 0xFF) << 8) | (in[idx + 1] & 0xFF);
    }
}
