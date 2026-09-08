/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Compresses/decompresses the message payload before embedding.
 * <p>
 * The payload a steganography channel carries is almost always tiny (a sentence, a short file), and
 * every byte spent on framing is a byte spent on extra cover modifications. A full GZIP stream pays a
 * fixed 18-byte wrapper (10-byte header + 8-byte CRC32/ISIZE trailer) that a short message can't earn
 * back, and generic compressors have no head start on the kind of short text this channel typically
 * carries. So new data is compressed as zlib-wrapped DEFLATE (RFC 1950: a 2-byte header plus a 4-byte
 * Adler-32 trailer, 6 bytes total instead of GZIP's 18), primed with a preset dictionary built from
 * this project's own localized UI strings (i.e. real, varied, non-user, already-shipped multilingual
 * text) &mdash; both sides already have the dictionary bytes, so priming it costs nothing on the wire.
 * The zlib wrapper (rather than fully headerless raw DEFLATE) is deliberate: its header and checksum
 * are what let decompression reliably reject corrupt or non-DEFLATE input instead of silently
 * producing garbage. If compressing still doesn't shrink the payload (e.g. it's already dense binary),
 * the data is stored as-is rather than paying any compression overhead at all.
 * <p>
 * The chosen method is recorded by the caller (see {@link com.openstego.desktop.OpenStegoConfig
 * #setCompressionMethod}) so it round-trips through the stego header; {@link #METHOD_GZIP_LEGACY}
 * exists purely so files written by older versions of this codebase keep decoding correctly.
 */
public final class CompressionCodec {

    /** No compression: the payload is stored as-is. */
    public static final int METHOD_NONE = 0;

    /** Legacy full-GZIP framing, kept read-only for files written before this codec existed. */
    public static final int METHOD_GZIP_LEGACY = 1;

    /** Headerless DEFLATE primed with {@link #dictionary()}. Used for all new writes. */
    public static final int METHOD_DEFLATE_DICT = 2;

    private static final String DICTIONARY_RESOURCE = "/compression/message.dict";

    private static volatile byte[] dictionary;

    private CompressionCodec() {
        // Utility class
    }

    /** The result of {@link #compress}: which method was actually used, and the resulting bytes. */
    public static final class Result {
        public final int method;
        public final byte[] data;

        private Result(int method, byte[] data) {
            this.method = method;
            this.data = data;
        }
    }

    /**
     * Compresses {@code raw} with {@link #METHOD_DEFLATE_DICT}, falling back to storing it unchanged
     * (as {@link #METHOD_NONE}) if that doesn't actually shrink it.
     */
    public static Result compress(byte[] raw) {
        if (raw.length == 0) {
            return new Result(METHOD_NONE, raw);
        }
        byte[] compressed = deflate(raw);
        return (compressed.length < raw.length) ? new Result(METHOD_DEFLATE_DICT, compressed) : new Result(METHOD_NONE, raw);
    }

    /** Reverses {@link #compress}, dispatching on the method recorded at embed time. */
    public static byte[] decompress(byte[] data, int method) throws OpenStegoException {
        switch (method) {
            case METHOD_NONE:
                return data;
            case METHOD_GZIP_LEGACY:
                return gunzip(data);
            case METHOD_DEFLATE_DICT:
                return inflate(data);
            default:
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.CORRUPT_DATA);
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setDictionary(dictionary());
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data) throws OpenStegoException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    // The zlib header's FDICT flag is what tells us a dictionary is expected -- this
                    // only fires once, right after the 2-byte header is parsed.
                    if (inflater.needsDictionary()) {
                        inflater.setDictionary(dictionary());
                        continue;
                    }
                    if (inflater.needsInput() || inflater.finished()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            throw new OpenStegoException(ex, OpenStego.NAMESPACE, OpenStegoErrors.CORRUPT_DATA);
        } finally {
            inflater.end();
        }
    }

    private static byte[] gunzip(byte[] data) throws OpenStegoException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
                GZIPInputStream zis = new GZIPInputStream(bis)) {
            return CommonUtil.streamToBytes(zis);
        } catch (IOException ex) {
            throw new OpenStegoException(ex, OpenStego.NAMESPACE, OpenStegoErrors.CORRUPT_DATA);
        }
    }

    private static byte[] dictionary() {
        byte[] d = dictionary;
        if (d == null) {
            synchronized (CompressionCodec.class) {
                d = dictionary;
                if (d == null) {
                    dictionary = d = loadDictionary();
                }
            }
        }
        return d;
    }

    private static byte[] loadDictionary() {
        try (InputStream is = CompressionCodec.class.getResourceAsStream(DICTIONARY_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing bundled resource: " + DICTIONARY_RESOURCE);
            }
            return is.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load " + DICTIONARY_RESOURCE, ex);
        }
    }
}
