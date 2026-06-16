/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
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

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Batch diagnostic: for the first N precovers, embeds a payload with the real SI-UNIWARD plugin and
 * dumps the luma cover and stego quantized coefficients, so an external (Python) DCTR pipeline can
 * score the <em>actual Java embedding</em> through the exact same decompression + features path used
 * by the embedding simulator &mdash; removing any JPEG-decoder measurement confound.
 * Format (big-endian): [int nImages] then per image [int bh][int bw][int qOff? no] quant(64 int)
 * cover(bh*bw*64 short) stego(bh*bw*64 short). Usage: CoeffDump &lt;coverDir&gt; &lt;out.bin&gt; &lt;quality&gt; &lt;payloadBytes&gt; &lt;N&gt;
 */
public final class CoeffDump {
    public static void main(String[] args) throws Exception {
        Class.forName(OpenStego.class.getName());
        File dir = new File(args[0]);
        String out = args[1];
        int q = Integer.parseInt(args[2]);
        int payload = Integer.parseInt(args[3]);
        int n = Integer.parseInt(args[4]);

        File[] files = dir.listFiles((d, nm) -> nm.toLowerCase().endsWith(".png"));
        Arrays.sort(files);
        int count = Math.min(n, files.length);

        PluginManager.loadPlugins();
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName("JpegUniward");
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(false);
        ((JpegUniwardConfig) plugin.getConfig()).setQuality(q);
        OpenStego os = new OpenStego(plugin, plugin.getConfig());

        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            o.writeInt(count);
            for (int f = 0; f < count; f++) {
                File pre = files[f];
                byte[] coverBytes = CommonUtil.fileToBytes(pre);
                PixelImage precover = ImageCodecRegistry.get().decode(coverBytes, pre.getName());
                JpegImage jc = JpegCodec.fromPrecover(precover, q);
                byte[] msg = new byte[payload];
                new Random(pre.getName().hashCode()).nextBytes(msg);
                byte[] stego = os.embedData(msg, "p.bin", coverBytes, pre.getName(), pre.getName());
                JpegImage js = JpegCodec.decode(stego);

                int c = 0;
                int bw = jc.getBlocksWide(c), bh = jc.getBlocksHigh(c);
                o.writeInt(bh); o.writeInt(bw);
                int[] quant = jc.getQuantTable(c);
                for (int k = 0; k < 64; k++) o.writeInt(quant[k]);
                for (int br = 0; br < bh; br++) {
                    for (int bc = 0; bc < bw; bc++) {
                        short[] blk = jc.getBlock(c, br, bc);
                        for (int k = 0; k < 64; k++) o.writeShort(blk[k]);
                    }
                }
                for (int br = 0; br < bh; br++) {
                    for (int bc = 0; bc < bw; bc++) {
                        short[] blk = js.getBlock(c, br, bc);
                        for (int k = 0; k < 64; k++) o.writeShort(blk[k]);
                    }
                }
            }
        }
        System.out.printf("dumped %d images -> %s%n", count, out);
    }
}
