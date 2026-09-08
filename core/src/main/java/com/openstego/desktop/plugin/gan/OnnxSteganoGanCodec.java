/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.gan;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.openstego.desktop.OpenStegoException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * Loads the bundled SteganoGAN "dense" architecture (encoder + decoder ONNX graphs, exported from the
 * DAI-Lab/SteganoGAN pretrained "dense.steg" checkpoint) and runs inference through ONNX Runtime.
 * <p>
 * {@code :core} depends on {@code onnxruntime}'s Java API as {@code compileOnly}: it compiles against
 * {@code ai.onnxruntime.*}, but does not bundle the runtime jar itself. Each consuming module supplies
 * its own platform-appropriate artifact at runtime -- {@code onnxruntime} (desktop/JVM) or
 * {@code onnxruntime-android} -- both of which implement the identical Java API surface. A consumer
 * that never touches this plugin never needs either dependency.
 * <p>
 * Fixed network parameters (from the pretrained checkpoint, not user-configurable): {@code DATA_DEPTH}
 * bits are embedded per pixel position, and both encoder and decoder are fully convolutional, so any
 * cover width/height works without retraining.
 */
final class OnnxSteganoGanCodec {

    /** Bits embedded per pixel position (fixed by the pretrained "dense" checkpoint). */
    static final int DATA_DEPTH = 8;

    private static final String ENCODER_RESOURCE = "/onnx/steganogan_dense_encoder.onnx";
    private static final String DECODER_RESOURCE = "/onnx/steganogan_dense_decoder.onnx";

    private volatile OrtEnvironment env;
    private volatile OrtSession encoderSession;
    private volatile OrtSession decoderSession;

    /**
     * Deliberately does no ONNX Runtime work: {@link com.openstego.desktop.util.PluginManager}
     * eagerly instantiates every registered plugin at app startup, so touching the native library here
     * would force-load it even for users who never pick this plugin. Everything is deferred to first
     * actual use, in {@link #env()}/{@link #createSession}.
     */
    OnnxSteganoGanCodec() {}

    private synchronized OrtEnvironment env() throws OpenStegoException {
        if (this.env == null) {
            try {
                this.env = OrtEnvironment.getEnvironment();
            } catch (LinkageError | Exception ex) {
                // LinkageError covers NoClassDefFoundError/UnsatisfiedLinkError when the consuming app
                // hasn't added an onnxruntime artifact to its runtime classpath.
                throw new OpenStegoException(ex, GanStegPlugin.NAMESPACE, GanStegErrors.ERR_MODEL_UNAVAILABLE);
            }
        }
        return this.env;
    }

    private synchronized OrtSession encoder() throws OpenStegoException {
        if (this.encoderSession == null) {
            this.encoderSession = createSession(ENCODER_RESOURCE);
        }
        return this.encoderSession;
    }

    private synchronized OrtSession decoder() throws OpenStegoException {
        if (this.decoderSession == null) {
            this.decoderSession = createSession(DECODER_RESOURCE);
        }
        return this.decoderSession;
    }

    private OrtSession createSession(String resourcePath) throws OpenStegoException {
        try (InputStream is = OnnxSteganoGanCodec.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new OpenStegoException(null, GanStegPlugin.NAMESPACE, GanStegErrors.ERR_MODEL_UNAVAILABLE);
            }
            byte[] modelBytes = is.readAllBytes();
            return env().createSession(modelBytes, new OrtSession.SessionOptions());
        } catch (IOException | OrtException ex) {
            throw new OpenStegoException(ex, GanStegPlugin.NAMESPACE, GanStegErrors.ERR_MODEL_UNAVAILABLE);
        }
    }

    /**
     * Embeds {@code payloadBits} into {@code coverRgb}, returning the stego pixel array in the same
     * packed {@code 0xRRGGBB} layout. {@code payloadBits.length} must equal {@code DATA_DEPTH * width *
     * height}, one bit per (depth, y, x) position, depth-major (matches the tensor layout the network
     * was trained on).
     */
    int[] encode(int[] coverRgb, int width, int height, boolean[] payloadBits) throws OpenStegoException {
        float[] coverChw = rgbToChw(coverRgb, width, height);

        float[] payloadChw = new float[DATA_DEPTH * width * height];
        for (int i = 0; i < payloadChw.length; i++) {
            payloadChw[i] = payloadBits[i] ? 1.0f : 0.0f;
        }

        try (OnnxTensor coverTensor =
                        OnnxTensor.createTensor(env(), FloatBuffer.wrap(coverChw), new long[] {1, 3, height, width});
                OnnxTensor payloadTensor = OnnxTensor.createTensor(
                        env(), FloatBuffer.wrap(payloadChw), new long[] {1, DATA_DEPTH, height, width})) {
            Map<String, OnnxTensor> inputs = Map.of("cover", coverTensor, "payload", payloadTensor);
            try (OrtSession.Result result = encoder().run(inputs)) {
                float[][][][] stegoChw = (float[][][][]) result.get(0).getValue();
                int[] stegoRgb = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int r = toByte(stegoChw[0][0][y][x]);
                        int g = toByte(stegoChw[0][1][y][x]);
                        int b = toByte(stegoChw[0][2][y][x]);
                        stegoRgb[y * width + x] = (r << 16) | (g << 8) | b;
                    }
                }
                return stegoRgb;
            }
        } catch (OrtException ex) {
            throw new OpenStegoException(ex, GanStegPlugin.NAMESPACE, GanStegErrors.ERR_MODEL_UNAVAILABLE);
        }
    }

    /**
     * Runs the decoder over {@code stegoRgb} and returns the raw (pre error-correction) bit decisions,
     * {@code DATA_DEPTH * width * height} entries, depth-major -- the caller is responsible for the
     * tiling/majority-vote/error-correction layer, since the network itself has a real (non-zero) raw
     * bit error rate and is not expected to be bit-perfect on its own.
     */
    boolean[] decodeBits(int[] stegoRgb, int width, int height) throws OpenStegoException {
        float[] stegoChw = rgbToChw(stegoRgb, width, height);

        try (OnnxTensor imageTensor =
                OnnxTensor.createTensor(env(), FloatBuffer.wrap(stegoChw), new long[] {1, 3, height, width})) {
            try (OrtSession.Result result = decoder().run(Collections.singletonMap("image", imageTensor))) {
                float[][][][] bitsChw = (float[][][][]) result.get(0).getValue();
                boolean[] bits = new boolean[DATA_DEPTH * width * height];
                int i = 0;
                for (int d = 0; d < DATA_DEPTH; d++) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            bits[i++] = bitsChw[0][d][y][x] >= 0.0f;
                        }
                    }
                }
                return bits;
            }
        } catch (OrtException ex) {
            throw new OpenStegoException(ex, GanStegPlugin.NAMESPACE, GanStegErrors.ERR_MODEL_UNAVAILABLE);
        }
    }

    /** Packed {@code 0xRRGGBB} pixels to a planar (CHW) float tensor, normalized to [-1, 1]. */
    private static float[] rgbToChw(int[] rgb, int width, int height) {
        float[] chw = new float[3 * width * height];
        int plane = width * height;
        for (int i = 0; i < plane; i++) {
            int p = rgb[i];
            chw[i] = (((p >> 16) & 0xFF) / 127.5f) - 1.0f;
            chw[plane + i] = (((p >> 8) & 0xFF) / 127.5f) - 1.0f;
            chw[2 * plane + i] = ((p & 0xFF) / 127.5f) - 1.0f;
        }
        return chw;
    }

    private static int toByte(float v) {
        int px = Math.round((v + 1.0f) * 127.5f);
        return Math.max(0, Math.min(255, px));
    }
}
