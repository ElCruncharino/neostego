/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.wavlsb;

import com.openstego.desktop.DataHidingPlugin;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.plugin.lsb.LSBDataHeader;
import com.openstego.desktop.util.LabelUtil;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Audio data-hiding plugin (upstream issue #5). Hides a message in the least-significant bit of each integer
 * PCM sample of an uncompressed WAV cover, reusing the standard OpenStego {@link LSBDataHeader} frame so that
 * compression and encryption keep working. Output is a WAV with the same format; the container bytes (RIFF
 * headers, format chunk, any other chunks) are preserved exactly so the file still plays normally.
 * <p>
 * Only lossless PCM WAV is supported. Lossy/compressed audio (MP3, AAC, Ogg, ...) cannot carry LSB data,
 * because the codec discards exactly the low-order detail the message lives in.
 */
public class WavLSBPlugin extends DataHidingPlugin<OpenStegoConfig> {

    /** Namespace for this plugin. */
    public static final String NAMESPACE = "WavLSB";

    private static final LabelUtil labelUtil = LabelUtil.getInstance(NAMESPACE);

    /**
     * Default constructor.
     */
    public WavLSBPlugin() {
        LabelUtil.addNamespace(NAMESPACE, "i18n.WavLSBPluginLabels");
        WavLSBErrors.init();
    }

    @Override
    public String getName() {
        return "WavLSB";
    }

    @Override
    public String getDescription() {
        return labelUtil.getString("plugin.description");
    }

    @Override
    public byte[] embedData(byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException {
        if (cover == null) {
            throw new OpenStegoException(null, NAMESPACE, WavLSBErrors.NO_COVER_FILE);
        }

        WavCodec wav = WavCodec.parse(cover);
        byte[] header = new LSBDataHeader(msg.length, 1, msgFileName, this.config).getHeaderData();

        long totalBits = ((long) header.length + msg.length) * 8L;
        if (totalBits > wav.sampleCount) {
            throw new OpenStegoException(null, NAMESPACE, WavLSBErrors.AUDIO_SIZE_INSUFFICIENT);
        }

        byte[] stego = cover.clone();
        int bitIndex = 0;
        bitIndex = writeBits(stego, wav, bitIndex, header);
        writeBits(stego, wav, bitIndex, msg);
        return stego;
    }

    /** Writes each byte (MSB-first) into the LSB of successive PCM samples; returns the next bit index. */
    private static int writeBits(byte[] stego, WavCodec wav, int bitIndex, byte[] data) {
        for (byte datum : data) {
            for (int b = 7; b >= 0; b--) {
                int bit = (datum >> b) & 1;
                int off = wav.sampleByteOffset(bitIndex);
                stego[off] = (byte) ((stego[off] & 0xFE) | bit);
                bitIndex++;
            }
        }
        return bitIndex;
    }

    @Override
    public String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException {
        WavCodec wav = WavCodec.parse(stegoData);
        try (WavLsbInputStream in = new WavLsbInputStream(stegoData, wav)) {
            return new LSBDataHeader(in, this.config).getFileName();
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    @Override
    public byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        WavCodec wav = WavCodec.parse(stegoData);
        try (WavLsbInputStream in = new WavLsbInputStream(stegoData, wav)) {
            LSBDataHeader header = new LSBDataHeader(in, this.config);
            byte[] data = new byte[header.getDataLength()];
            int read = in.read(data, 0, data.length);
            if (read != data.length) {
                throw new OpenStegoException(null, NAMESPACE, WavLSBErrors.ERR_AUDIO_DATA_READ);
            }
            return data;
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    @Override
    public byte[] getDiff(
            byte[] stegoData, String stegoFileName, byte[] coverData, String coverFileName, String diffFileName)
            throws OpenStegoException {
        WavCodec cover = WavCodec.parse(coverData);
        WavCodec stego = WavCodec.parse(stegoData);
        if (cover.sampleCount != stego.sampleCount || cover.bytesPerSample != stego.bytesPerSample) {
            throw new OpenStegoException(null, NAMESPACE, WavLSBErrors.UNSUPPORTED_WAV_FORMAT);
        }
        // Emit the stego container with each data byte replaced by the (amplified) per-byte difference, so the
        // changed samples stand out audibly/visibly.
        byte[] diff = stegoData.clone();
        for (int i = 0; i < cover.dataLength && (cover.dataOffset + i) < coverData.length; i++) {
            int d = Math.abs((coverData[cover.dataOffset + i] & 0xFF) - (stegoData[stego.dataOffset + i] & 0xFF));
            diff[stego.dataOffset + i] = (byte) Math.min(255, d * 64);
        }
        return diff;
    }

    @Override
    public List<String> getReadableFileExtensions() {
        return Arrays.asList("wav", "wave");
    }

    @Override
    public List<String> getWritableFileExtensions() {
        return Arrays.asList("wav", "wave");
    }

    @Override
    public String getUsage() {
        return labelUtil.getString("plugin.usage");
    }

    @Override
    protected OpenStegoConfig createConfig() {
        return new OpenStegoConfig();
    }

    /**
     * Reads bytes (MSB-first) out of the least-significant bit of successive PCM samples - the inverse of
     * {@link #writeBits}. Exposed as an {@link InputStream} so {@link LSBDataHeader} can parse the header.
     */
    private static final class WavLsbInputStream extends InputStream {
        private final byte[] stego;
        private final WavCodec wav;
        private int sampleIndex;

        WavLsbInputStream(byte[] stego, WavCodec wav) {
            this.stego = stego;
            this.wav = wav;
        }

        @Override
        public int read() {
            int value = 0;
            for (int b = 7; b >= 0; b--) {
                if (this.sampleIndex >= this.wav.sampleCount) {
                    return -1;
                }
                int bit = this.stego[this.wav.sampleByteOffset(this.sampleIndex)] & 1;
                value |= (bit << b);
                this.sampleIndex++;
            }
            return value;
        }
    }
}
