/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.PluginManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the CMD (v2) embedding path of the content-adaptive plugin: v2 round-trip across
 * payloads / MU settings / encryption, the &plusmn;1 invariant, saturated-boundary covers, and that
 * v1 stego still decodes on the same (v2-aware) build so the format flag is backward compatible.
 */
public class AdaptiveCmdTest {

    private static byte[] coverBytes;

    @BeforeAll
    public static void setUp() throws Exception {
        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();
        try (InputStream is = AdaptiveCmdTest.class.getResourceAsStream("/compat/cover.png")) {
            assertNotNull(is, "cover.png test resource must exist");
            coverBytes = CommonUtil.streamToBytes(is);
        }
    }

    private static OpenStego newStego(boolean cmd, double mu, boolean encrypt, String password) throws Exception {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("Adaptive");
        assertNotNull(plugin, "Adaptive plugin must be registered");
        plugin.resetConfig();
        AdaptiveConfig cfg = (AdaptiveConfig) plugin.getConfig();
        cfg.setUseCompression(false);
        cfg.setUseEncryption(encrypt);
        if (password != null) {
            cfg.setPassword(password);
        }
        cfg.setCmd(cmd);
        cfg.setCmdMu(mu);
        return new OpenStego(plugin, cfg);
    }

    private static byte[] solidPng(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    public void v2RoundTripAcrossPayloadsAndMu() throws Exception {
        int[] payloads = {1, 16, 500, 4000, 20000};
        double[] mus = {1.0, 3.0, 9.0};
        for (double mu : mus) {
            for (int len : payloads) {
                byte[] msg = new byte[len];
                new Random(len * 1000 + (long) mu).nextBytes(msg);
                byte[] stego =
                        newStego(true, mu, false, null).embedData(msg, "m.bin", coverBytes, "cover.png", "stego.png");
                // Decoder auto-detects v2 from the width-field sentinel; its own cmd flag is irrelevant.
                List<?> out = newStego(false, 9.0, false, null).extractData(stego, "stego.png");
                assertArrayEquals(
                        msg, (byte[]) out.get(1), "v2 round-trip must be lossless (mu=" + mu + ", len=" + len + ")");
            }
        }
    }

    @Test
    public void v2EncryptedRoundTrip() throws Exception {
        byte[] msg = new byte[4096];
        new Random(7).nextBytes(msg);
        String pw = "stay frosty";
        byte[] stego = newStego(true, 9.0, true, pw).embedData(msg, "secret.bin", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego(true, 9.0, true, pw).extractData(stego, "stego.png");
        assertEquals("secret.bin", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void v1StegoStillDecodesOnV2AwareBuild() throws Exception {
        byte[] msg = "legacy v1 payload must still decode".getBytes(StandardCharsets.UTF_8);
        byte[] stego =
                newStego(false, 9.0, false, null).embedData(msg, "old.txt", coverBytes, "cover.png", "stego.png");
        List<?> out = newStego(false, 9.0, false, null).extractData(stego, "stego.png");
        assertEquals("old.txt", out.get(0));
        assertArrayEquals(msg, (byte[]) out.get(1));
    }

    @Test
    public void v2EmbeddingChangesChannelsByAtMostOne() throws Exception {
        byte[] msg = new byte[3000];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) (i * 31 + 7);
        }
        byte[] stego = newStego(true, 9.0, false, null).embedData(msg, "m.bin", coverBytes, "cover.png", "stego.png");

        ImageHolder coverImg = ImageUtil.byteArrayToImage(coverBytes, "cover.png");
        ImageHolder stegoImg = ImageUtil.byteArrayToImage(stego, "stego.png");
        int w = coverImg.getImage().getWidth();
        int h = coverImg.getImage().getHeight();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int cp = coverImg.getImage().getRGB(x, y);
                int sp = stegoImg.getImage().getRGB(x, y);
                for (int ch = 0; ch < 3; ch++) {
                    int cv = (cp >> (ch * 8)) & 0xFF;
                    int sv = (sp >> (ch * 8)) & 0xFF;
                    assertTrue(
                            Math.abs(cv - sv) <= 1, "Each channel must change by at most 1; got " + cv + " -> " + sv);
                }
            }
        }
    }

    @Test
    public void v2RoundTripOnSaturatedCovers() throws Exception {
        byte[] msg = new byte[2000];
        new Random(99).nextBytes(msg);
        for (int rgb : new int[] {0x000000, 0xFFFFFF}) {
            byte[] cover = solidPng(200, 200, rgb);
            byte[] stego = newStego(true, 9.0, false, null).embedData(msg, "m.bin", cover, "flat.png", "stego.png");
            List<?> out = newStego(true, 9.0, false, null).extractData(stego, "stego.png");
            assertArrayEquals(
                    msg,
                    (byte[]) out.get(1),
                    "saturated cover round-trip must be lossless (rgb=" + Integer.toHexString(rgb) + ")");
        }
    }
}
