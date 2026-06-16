/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.dwtsvd;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the robust DWT-SVD watermarking plugin. The decisive properties are: a clean PNG round-trip
 * recovers the mark perfectly (correlation = 1), the mark survives JPEG re-compression and additive noise (high
 * correlation), a wrong signature reads as absent (low correlation), and embedding stays visually faithful (high PSNR).
 */
public class DWTSVDPluginTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = DWTSVDPluginTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("DWTSVD");
        assertNotNull(plugin, "DWTSVD plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setPassword(password);
        return new OpenStego(plugin, plugin.getConfig());
    }

    @Test
    public void cleanRoundTripRecoversMarkPerfectly() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        double corr = stego.checkMark(wm, "stego.png", sig);
        assertTrue(corr > 0.999, "clean round-trip correlation should be ~1.0, got " + corr);
    }

    @Test
    public void survivesJpegRecompression() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        for (float quality : new float[]{0.9f, 0.75f, 0.6f}) {
            byte[] jpeg = recompressJpeg(wm, quality);
            double corr = stego.checkMark(jpeg, "stego.jpg", sig);
            assertTrue(corr > 0.7, "JPEG Q" + (int) (quality * 100) + " correlation should stay high, got " + corr);
        }
    }

    @Test
    public void survivesAdditiveNoise() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        byte[] noisy = addGaussianNoise(wm, 3.0, 1234);
        double corr = stego.checkMark(noisy, "stego.png", sig);
        assertTrue(corr > 0.7, "additive-noise correlation should stay high, got " + corr);
    }

    @Test
    public void survivesGlobalBrightnessScaling() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        // A global brightness gain multiplies every pixel (and hence every singular value); the mu-normalized
        // quantizer step scales with it, so the watermark must survive. This is the valumetric case the absolute-step
        // scheme failed on.
        for (double gain : new double[]{0.85, 1.15}) {
            byte[] scaled = scaleBrightness(wm, gain);
            double corr = stego.checkMark(scaled, "stego.png", sig);
            assertTrue(corr > 0.7, "brightness x" + gain + " correlation should stay high, got " + corr);
        }
    }

    @Test
    public void wrongSignatureReadsAsAbsent() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        byte[] otherSig = newStego("different-key").generateSignature();
        double corr = stego.checkMark(wm, "stego.png", otherSig);
        assertTrue(corr < 0.2, "a different signature must read as absent, got " + corr);
    }

    @Test
    public void embeddingIsVisuallyFaithful() throws Exception {
        OpenStego stego = newStego("watermark-key");
        byte[] sig = stego.generateSignature();
        byte[] wm = stego.embedMark(sig, "key.sig", coverBytes, "cover.png", "stego.png");

        double psnr = psnr(coverBytes, wm);
        assertTrue(psnr > 38.0, "watermark PSNR should be high (>38 dB), got " + psnr);
    }

    // ------------------------------------------------------------------
    // Attack / metric helpers
    // ------------------------------------------------------------------

    private static byte[] recompressJpeg(byte[] pngData, float quality) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
        // The shared codec normalizes covers to TYPE_INT_ARGB, so the stego PNG carries an (opaque) alpha
        // channel. The baseline JPEG writer cannot encode alpha, so flatten to RGB first - this mirrors what
        // production JPEG export does (ImageUtil.flattenAlpha, issue #58).
        if (img.getColorModel().hasAlpha()) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = rgb;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        }
        writer.dispose();
        return baos.toByteArray();
    }

    private static byte[] addGaussianNoise(byte[] pngData, double sigma, long seed) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
        Random rnd = new Random(seed);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = clamp(((rgb >> 16) & 0xff) + (int) Math.round(rnd.nextGaussian() * sigma));
                int g = clamp(((rgb >> 8) & 0xff) + (int) Math.round(rnd.nextGaussian() * sigma));
                int b = clamp((rgb & 0xff) + (int) Math.round(rnd.nextGaussian() * sigma));
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static byte[] scaleBrightness(byte[] pngData, double gain) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = clamp((int) Math.round(((rgb >> 16) & 0xff) * gain));
                int g = clamp((int) Math.round(((rgb >> 8) & 0xff) * gain));
                int b = clamp((int) Math.round((rgb & 0xff) * gain));
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static double psnr(byte[] aPng, byte[] bPng) throws Exception {
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(aPng));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(bPng));
        long sse = 0;
        long count = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                for (int ch = 0; ch < 3; ch++) {
                    int va = (pa >> (ch * 8)) & 0xff;
                    int vb = (pb >> (ch * 8)) & 0xff;
                    long d = va - vb;
                    sse += d * d;
                    count++;
                }
            }
        }
        if (sse == 0) {
            return 99.0;
        }
        double mse = (double) sse / count;
        return 10.0 * Math.log10(255.0 * 255.0 / mse);
    }
}
