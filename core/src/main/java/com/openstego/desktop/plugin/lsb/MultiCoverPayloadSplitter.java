/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoCrypto;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.CommonUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Splits a single payload across several cover images and reassembles it on extraction
 * (upstream issue #67). This lifts the per-image LSB capacity ceiling: a file that does not fit in
 * one cover can be spread over a series of pictures.
 * <p>
 * The whole payload is compressed and encrypted <em>once</em> (reusing the same GZIP +
 * {@link OpenStegoCrypto} logic as {@link OpenStego}), then divided into chunks. Each chunk is
 * prefixed with a {@link MultiPartSplitManifest} and embedded into one cover via the ordinary LSB
 * plugin with the plugin's own compression/encryption switched off (the data is already processed).
 * Because nothing about the on-image {@link LSBDataHeader} format changes, existing single-image
 * stego files extract exactly as before.
 * <p>
 * Note on scope: the payload is still handled in memory as a single {@code byte[]}, so the total
 * size remains bounded by the JVM heap (and the ~2&nbsp;GB array limit). This removes the
 * single-image limit, not the in-memory one; true streaming of multi-gigabyte payloads is out of
 * scope.
 */
public final class MultiCoverPayloadSplitter {

    private MultiCoverPayloadSplitter() {
    }

    /**
     * Splits a payload across the given covers, producing one stego image per cover.
     *
     * @param payload         The original (uncompressed, unencrypted) message bytes
     * @param msgFileName     The original file name (embedded in part 0 when enabled in config)
     * @param covers          Cover image bytes, one per output image (at least 2)
     * @param coverFileNames  File names for the covers (used to pick the image codec)
     * @param stegoFileNames  Output stego file names (used to pick the output image codec)
     * @param config          Configuration carrying compression/encryption settings and password
     * @param plugin          The image LSB plugin used to embed each part
     * @return One stego image (byte array) per cover, in the same order as {@code covers}
     * @throws OpenStegoException If inputs are inconsistent or the covers cannot hold the payload
     */
    public static List<byte[]> embedSplit(byte[] payload, String msgFileName, List<byte[]> covers,
                                          List<String> coverFileNames, List<String> stegoFileNames,
                                          OpenStegoConfig config, DHImagePluginTemplate<?> plugin) throws OpenStegoException {
        OpenStego.init(); // ensure the core label namespace and error codes are registered
        int n = covers.size();
        if (n < 2 || coverFileNames.size() != n || stegoFileNames.size() != n) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_REQUIRES_MULTIPLE_COVERS, n);
        }

        boolean useCompression = config.isUseCompression();
        boolean useEncryption = config.isUseEncryption();
        String algo = config.getEncryptionAlgorithm();
        String fileName = config.isEmbedFileName() ? msgFileName : null;

        // 1) Compress + encrypt the whole payload once, mirroring OpenStego.embedData's order.
        byte[] processed = payload;
        try {
            if (useCompression) {
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); GZIPOutputStream zos = new GZIPOutputStream(bos)) {
                    zos.write(processed);
                    zos.finish();
                    zos.flush();
                    processed = bos.toByteArray();
                }
            }
            if (useEncryption) {
                OpenStegoCrypto crypto = new OpenStegoCrypto(config.getPassword(), algo, config.isUseStrongEncryption());
                processed = crypto.encrypt(processed);
            }
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }

        int totalLength = processed.length;
        int sessionId = new SecureRandom().nextInt();

        // 2) Work out the chunk capacity of each cover (capacity minus this part's manifest overhead).
        int[] capacity = new int[n];
        for (int i = 0; i < n; i++) {
            PixelImage image = ImageCodecRegistry.get().decode(covers.get(i), coverFileNames.get(i));
            int manifestSize = manifestSizeFor(i, fileName);
            int max = plugin.getMaxDataLength(image);
            if (max < manifestSize) {
                // The cover is too small to hold even the per-part metadata.
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_INSUFFICIENT_CAPACITY, totalLength);
            }
            capacity[i] = max - manifestSize;
        }

        // 3) Greedily fill covers in order; every cover still produces a part (possibly empty).
        int[] chunkLen = new int[n];
        int remaining = totalLength;
        for (int i = 0; i < n; i++) {
            int take = Math.min(remaining, capacity[i]);
            chunkLen[i] = take;
            remaining -= take;
        }
        if (remaining > 0) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_INSUFFICIENT_CAPACITY, totalLength);
        }

        // 4) Embed each part. The data is already processed, so the per-image LSB header must record
        //    no compression/encryption; restore the caller's flags afterwards.
        List<byte[]> result = new ArrayList<>(n);
        boolean savedComp = config.isUseCompression();
        boolean savedEnc = config.isUseEncryption();
        try {
            config.setUseCompression(false);
            config.setUseEncryption(false);
            int offset = 0;
            for (int i = 0; i < n; i++) {
                MultiPartSplitManifest manifest = new MultiPartSplitManifest(sessionId, i, n, totalLength, chunkLen[i],
                        useCompression, useEncryption, algo, (i == 0) ? fileName : null);
                byte[] manifestBytes = manifest.toBytes();
                byte[] partPayload = new byte[manifestBytes.length + chunkLen[i]];
                System.arraycopy(manifestBytes, 0, partPayload, 0, manifestBytes.length);
                System.arraycopy(processed, offset, partPayload, manifestBytes.length, chunkLen[i]);
                offset += chunkLen[i];

                result.add(plugin.embedData(partPayload, null, covers.get(i), coverFileNames.get(i), stegoFileNames.get(i)));
            }
        } finally {
            config.setUseCompression(savedComp);
            config.setUseEncryption(savedEnc);
        }
        return result;
    }

    /**
     * Reassembles a payload from a set of split stego images.
     *
     * @param stegoImages    Stego image bytes, one per part (any order; at least 2)
     * @param stegoFileNames File names for the stego images (used to pick the image codec)
     * @param config         Configuration carrying the password used for decryption
     * @param plugin         The image LSB plugin used to extract each part
     * @return A two-element list: the original file name and the reassembled message bytes
     * @throws OpenStegoException If parts are missing, duplicated, corrupt, or from different splits
     */
    public static List<?> extractSplit(List<byte[]> stegoImages, List<String> stegoFileNames,
                                       OpenStegoConfig config, DHImagePluginTemplate<?> plugin) throws OpenStegoException {
        OpenStego.init(); // ensure the core label namespace and error codes are registered
        int n = stegoImages.size();
        if (n < 2 || stegoFileNames.size() != n) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_REQUIRES_MULTIPLE_PARTS, n);
        }

        // Capture the password up front: reading each part's LSB header mutates the config's
        // compression/encryption/algorithm fields (but not the password).
        char[] password = config.getPassword();

        MultiPartSplitManifest[] ordered = null;
        byte[][] chunks = null;
        Integer sessionId = null;
        int totalParts = -1;

        for (int i = 0; i < n; i++) {
            byte[] raw = plugin.extractData(stegoImages.get(i), stegoFileNames.get(i), null);
            MultiPartSplitManifest manifest = MultiPartSplitManifest.parse(raw);

            if (sessionId == null) {
                sessionId = manifest.getSessionId();
                totalParts = manifest.getTotalParts();
                if (n != totalParts) {
                    throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_INCOMPLETE, totalParts, n);
                }
                ordered = new MultiPartSplitManifest[totalParts];
                chunks = new byte[totalParts][];
            } else if (manifest.getSessionId() != sessionId || manifest.getTotalParts() != totalParts) {
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_MISMATCH);
            }

            int idx = manifest.getPartIndex();
            if (ordered[idx] != null) {
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_CORRUPT,
                        "duplicate part index " + idx);
            }
            ordered[idx] = manifest;
            chunks[idx] = manifest.getChunk(raw);
        }

        // 2) Concatenate chunks in order.
        int totalLength = ordered[0].getTotalLength();
        byte[] processed = new byte[totalLength];
        int offset = 0;
        for (int i = 0; i < totalParts; i++) {
            byte[] chunk = chunks[i];
            if (offset + chunk.length > totalLength) {
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_CORRUPT,
                        "reassembled length exceeds declared total");
            }
            System.arraycopy(chunk, 0, processed, offset, chunk.length);
            offset += chunk.length;
        }
        if (offset != totalLength) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_MANIFEST_CORRUPT,
                    "reassembled length does not match declared total");
        }

        // 3) Reverse encryption then compression, using the flags recorded in the manifest.
        MultiPartSplitManifest first = ordered[0];
        byte[] msg = processed;
        try {
            if (first.isUseEncryption()) {
                OpenStegoCrypto crypto = new OpenStegoCrypto(password, first.getEncryptionAlgorithm());
                msg = crypto.decrypt(msg);
            }
            if (first.isUseCompression()) {
                try (ByteArrayInputStream bis = new ByteArrayInputStream(msg); GZIPInputStream zis = new GZIPInputStream(bis)) {
                    msg = CommonUtil.streamToBytes(zis);
                } catch (IOException ioEx) {
                    throw new OpenStegoException(ioEx, OpenStego.NAMESPACE, OpenStegoErrors.CORRUPT_DATA);
                }
            }
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }

        List<Object> output = new ArrayList<>(2);
        output.add(first.getFileName());
        output.add(msg);
        return output;
    }

    private static int manifestSizeFor(int partIndex, String fileName) {
        int extra = (partIndex == 0 && fileName != null) ? fileName.getBytes(StandardCharsets.UTF_8).length : 0;
        return MultiPartSplitManifest.FIXED_SIZE + extra;
    }
}
