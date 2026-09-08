/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.dwtsvd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.PluginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the robust data-hiding plugin: exact-byte message recovery through the same
 * attacks {@link DWTSVDPluginTest}/{@link DWTSVDCropTest} already prove the underlying QIM channel
 * survives (JPEG re-compression, additive noise, brightness scaling, small edge crops), reusing those
 * classes' attack simulators directly since the channel and the attacks are identical -- only the
 * payload (exact bytes vs. a correlation score) differs -- plus a geometric resize, which the watermark
 * path doesn't attempt and this plugin's DCT-domain layer (see {@link SvdQimChannel}) does.
 * <p>
 * Uses a larger synthetic cover than the shared 400x300 {@code cover.png}: this plugin's fixed message
 * budget is bytes, not the watermark's 64 bits, so it needs more LL blocks for the same redundancy
 * margin -- representative of the real target (an actual photo), not a thumbnail.
 */
public class RobustPluginTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        coverBytes = syntheticCover(3200, 2400);
    }

    /** A deterministic, textured (not flat) synthetic cover, large enough for a comfortable redundancy margin. */
    private static byte[] syntheticCover(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Floored well away from 0/255: a corner or edge that clips to pure black/white would
                // trigger RobustPlugin's near-boundary block exclusion, which is exercised deliberately
                // and separately against real photos, not this synthetic gradient-plus-noise texture.
                int base = 40 + (x * 175 / width + y * 175 / height) / 2;
                int noise = rnd.nextInt(40) - 20;
                int v = Math.max(0, Math.min(255, base + noise));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static OpenStego newStego(String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("Robust");
        assertNotNull(plugin, "Robust plugin must be registered");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        return new OpenStego(plugin, plugin.getConfig());
    }

    private static byte[] embed(byte[] msg) throws Exception {
        return newStego("robust-key").embedData(msg, "note.txt", coverBytes, "cover.png", "stego.png");
    }

    @Test
    public void cleanRoundTrip() throws Exception {
        byte[] msg = "a message that must survive".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        List<?> out = newStego("robust-key").extractData(stego, "stego.png");
        assertEquals("note.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    /**
     * Regression test for a real bug: a large near-black region (deep shadow, common in real photos but
     * absent from the evenly-textured {@link #coverBytes}) used to corrupt a clean, unattacked round trip
     * outright, because the QIM step was calibrated to the whole image's mean block energy -- wildly wrong
     * for a block whose own energy is a tiny fraction of that mean -- and forcing such a block through the
     * nearest correctly-paritied quantization level pushed it into the final inverse-DWT pixel clamp.
     * Fixed by a locally-adaptive step, excluding blocks too close to that boundary to safely quantize, and
     * escalating strength (see {@link RobustPlugin}'s {@code STRENGTH_ESCALATION}) when too many blocks
     * end up excluded at the base strength.
     */
    @Test
    public void survivesLargeNearBlackRegion() throws Exception {
        byte[] shadowedCover = coverWithShadow(3200, 2400);
        byte[] msg = "still here despite the shadow".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego("robust-key").embedData(msg, "note.txt", shadowedCover, "cover.png", "stego.png");

        List<?> out = newStego("robust-key").extractData(stego, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    /** As {@link #syntheticCover}, but the left half is near-black -- a synthetic stand-in for a real photo's shadow. */
    private static byte[] coverWithShadow(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(43);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean shadow = x < width / 2;
                int base = shadow ? 0 : 40 + (x * 175 / width + y * 175 / height) / 2;
                int noiseRange = shadow ? 6 : 40;
                int noise = rnd.nextInt(noiseRange) - noiseRange / 2;
                int v = Math.max(0, Math.min(255, base + noise));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * Regression test for the gap {@link #survivesLargeNearBlackRegion} didn't cover: escalation used to
     * verify against a clean readback only, so a hard cover that needed escalation to round-trip at all
     * could still fail as soon as real JPEG recompression was added on top -- found by comparing against a
     * real photograph, not this synthetic stand-in, but reproduced here so it stays caught. Fixed by
     * verifying escalation against a simulated recompression (see {@link RobustPlugin}'s {@code
     * survivesRecompression}) instead of just the inverse-DWT's own pixel clamp.
     */
    @Test
    public void survivesLargeNearBlackRegionThenJpegRecompression() throws Exception {
        byte[] shadowedCover = coverWithShadow(3200, 2400);
        byte[] msg = "still here despite the shadow and jpeg".getBytes(StandardCharsets.UTF_8);
        byte[] stego = newStego("robust-key").embedData(msg, "note.txt", shadowedCover, "cover.png", "stego.png");

        byte[] jpeg = DWTSVDPluginTest.recompressJpeg(stego, 0.6f); // QF~62, this plugin's own escalation bar
        List<?> out = newStego("robust-key").extractData(jpeg, "stego.jpg");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void survivesJpegRecompression() throws Exception {
        byte[] msg = "still here after jpeg".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        // 0.6 (QF~62) is what a competing tool's Fortress mode advertises surviving; 0.2 is well past
        // that, backing this plugin's own javadoc claim.
        for (float quality : new float[] {0.9f, 0.75f, 0.6f, 0.2f}) {
            byte[] jpeg = DWTSVDPluginTest.recompressJpeg(stego, quality);
            List<?> out = newStego("robust-key").extractData(jpeg, "stego.jpg");
            assertArrayEquals(msg, (byte[]) out.get(1), "JPEG Q" + (int) (quality * 100) + " should still decode exactly");
        }
    }

    @Test
    public void survivesAdditiveNoise() throws Exception {
        byte[] msg = "still here after noise".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        byte[] noisy = DWTSVDPluginTest.addGaussianNoise(stego, 3.0, 1234);
        List<?> out = newStego("robust-key").extractData(noisy, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void survivesGlobalBrightnessScaling() throws Exception {
        byte[] msg = "still here after brightness".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        for (double gain : new double[] {0.85, 1.15}) {
            byte[] scaled = DWTSVDPluginTest.scaleBrightness(stego, gain);
            List<?> out = newStego("robust-key").extractData(scaled, "stego.png");
            assertArrayEquals(msg, (byte[]) out.get(1), "brightness x" + gain + " should still decode exactly");
        }
    }

    @Test
    public void survivesSmallEdgeCrop() throws Exception {
        byte[] msg = "still here after crop".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        for (int inset : new int[] {6, 8, 12}) {
            byte[] cropped = cropEdges(stego, inset);
            List<?> out = newStego("robust-key").extractData(cropped, "stego.png");
            assertArrayEquals(msg, (byte[]) out.get(1), inset + "px edge crop should still decode exactly");
        }
    }

    @Test
    public void survivesUniformResize() throws Exception {
        byte[] msg = "still here after resize".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        byte[] resized = resize(stego, 0.9);
        List<?> out = newStego("robust-key").extractData(resized, "stego.png");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void survivesResizeThenJpegRecompression() throws Exception {
        byte[] msg = "still here after resize and jpeg".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        byte[] resized = resize(stego, 0.9);
        byte[] jpeg = DWTSVDPluginTest.recompressJpeg(resized, 0.85f);
        List<?> out = newStego("robust-key").extractData(jpeg, "stego.jpg");
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void wrongPasswordFailsCleanly() throws Exception {
        byte[] msg = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        assertThrows(OpenStegoException.class, () -> newStego("wrong-key").extractData(stego, "stego.png"));
    }

    @Test
    public void embeddingIsVisuallyFaithful() throws Exception {
        byte[] msg = "hi".getBytes(StandardCharsets.UTF_8);
        byte[] stego = embed(msg);

        double psnr = DWTSVDPluginTest.psnr(coverBytes, stego);
        assertTrue(psnr > 38.0, "embedding PSNR should be high (>38 dB), got " + psnr);
    }

    /** Rescales both dimensions by {@code scale} (bilinear) and re-encodes as PNG -- a pure resize, no crop. */
    private static byte[] resize(byte[] png, double scale) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int nw = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int nh = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage resized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = resized.createGraphics();
        g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "png", out);
        return out.toByteArray();
    }

    /** Removes {@code inset} pixels from each edge (a pure crop, no rescale) and re-encodes as PNG. */
    private static byte[] cropEdges(byte[] png, int inset) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage cropped = img.getSubimage(inset, inset, img.getWidth() - 2 * inset, img.getHeight() - 2 * inset);
        BufferedImage copy = new BufferedImage(cropped.getWidth(), cropped.getHeight(), BufferedImage.TYPE_INT_RGB);
        copy.getGraphics().drawImage(cropped, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(copy, "png", out);
        return out.toByteArray();
    }
}
