/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Diagnostic: embeds a payload with the real SI-UNIWARD plugin, then dumps for the luma component the
 * precover plane, quant table, the SI-scaled UNIWARD cost, the rounding error and a changed-flag per
 * AC coefficient (cover vs stego). Lets an independent reference rank the <em>realised</em> changes by
 * true cost &mdash; the decisive security test (are changes concentrated in genuinely cheap spots?).
 * Usage: ChangeDump &lt;precover.png&gt; &lt;out.bin&gt; &lt;quality&gt; &lt;payloadBytes&gt; [password]
 */
public final class ChangeDump {
    public static void main(String[] args) throws Exception {
        Class.forName(OpenStego.class.getName());
        File pre = new File(args[0]);
        String out = args[1];
        int q = Integer.parseInt(args[2]);
        int payload = Integer.parseInt(args[3]);
        String password = args.length > 4 && !args[4].isEmpty() ? args[4] : null;

        byte[] coverBytes = CommonUtil.fileToBytes(pre);
        PixelImage precover = ImageCodecRegistry.get().decode(coverBytes, pre.getName());
        JpegImage jc = JpegCodec.fromPrecover(precover, q);
        int c = 0;
        double[][] plane = jc.getPlane(c);
        int H = plane.length, W = plane[0].length;
        int bw = jc.getBlocksWide(c), bh = jc.getBlocksHigh(c);
        int[] quant = jc.getQuantTable(c);
        double[][] base = UniwardCost.compute(plane, H, W, bw, bh, quant);

        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(password != null);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        ((JpegUniwardConfig) plugin.getConfig()).setQuality(q);
        OpenStego os = new OpenStego(plugin, plugin.getConfig());
        byte[] msg = new byte[payload];
        new Random(pre.getName().hashCode()).nextBytes(msg);
        byte[] stego = os.embedData(msg, "p.bin", coverBytes, pre.getName(), pre.getName());
        JpegImage js = JpegCodec.decode(stego);

        int nb = bw * bh;
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(out))) {
            o.writeInt(H); o.writeInt(W);
            for (int r = 0; r < H; r++) for (int cc = 0; cc < W; cc++) o.writeDouble(plane[r][cc]);
            o.writeInt(64);
            for (int k = 0; k < 64; k++) o.writeDouble(quant[k]);
            o.writeInt(nb); o.writeInt(bw); o.writeInt(bh);
            long changed = 0, z2nz = 0;
            for (int br = 0; br < bh; br++) {
                for (int bc = 0; bc < bw; bc++) {
                    int bi = br * bw + bc;
                    double[] e = jc.getRounding(c, br, bc);
                    short[] a = jc.getBlock(c, br, bc);
                    short[] b = js.getBlock(c, br, bc);
                    for (int k = 0; k < 64; k++) {
                        double costSi = (k == 0) ? 0.0 : base[bi][k] * (1.0 - 2.0 * Math.abs(e[k]));
                        int chg = (k >= 1 && a[k] != b[k]) ? 1 : 0;
                        if (chg == 1) { changed++; if (a[k] == 0) z2nz++; }
                        o.writeDouble(costSi);
                        o.writeDouble(e[k]);
                        o.writeDouble(chg);
                    }
                }
            }
            System.out.printf("dumped luma %dx%d blocks=%dx%d changes=%d (z2nz=%d) -> %s%n",
                    H, W, bw, bh, changed, z2nz, out);
        }
    }
}
