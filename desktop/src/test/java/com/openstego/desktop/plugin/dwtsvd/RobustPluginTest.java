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
 * payload (exact bytes vs. a correlation score) differs.
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
        coverBytes = syntheticCover(1600, 1200);
    }

    /** A deterministic, textured (not flat) synthetic cover, large enough for a comfortable redundancy margin. */
    private static byte[] syntheticCover(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (x * 255 / width + y * 255 / height) / 2;
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
