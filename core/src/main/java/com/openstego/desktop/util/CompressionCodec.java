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
 * Compresses the message payload with zlib-DEFLATE (6-byte overhead vs. GZIP's 18) primed with a
 * preset dictionary built from this project's own localized UI strings, falling back to storing the
 * payload as-is if that doesn't shrink it. {@link #METHOD_GZIP_LEGACY} is read-only, for files written
 * before this codec existed.
 */
public final class CompressionCodec {

    public static final int METHOD_NONE = 0;
    public static final int METHOD_GZIP_LEGACY = 1;
    public static final int METHOD_DEFLATE_DICT = 2;

    private static final String DICTIONARY_RESOURCE = "/compression/message.dict";

    private static final byte[] DICTIONARY = loadDictionary();

    private CompressionCodec() {}

    /** Which method was actually used, and the resulting bytes. */
    public static final class Result {
        public final int method;
        public final byte[] data;

        private Result(int method, byte[] data) {
            this.method = method;
            this.data = data;
        }
    }

    public static Result compress(byte[] raw) {
        if (raw.length == 0) {
            return new Result(METHOD_NONE, raw);
        }
        byte[] compressed = deflate(raw);
        return (compressed.length < raw.length)
                ? new Result(METHOD_DEFLATE_DICT, compressed)
                : new Result(METHOD_NONE, raw);
    }

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
            deflater.setDictionary(DICTIONARY);
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
                    if (inflater.needsDictionary()) {
                        inflater.setDictionary(DICTIONARY);
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
