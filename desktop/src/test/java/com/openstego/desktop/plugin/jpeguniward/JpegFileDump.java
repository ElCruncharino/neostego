/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.util.CommonUtil;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Decodes existing baseline JPEG files (e.g. the benchmark cover/stego sets) with NeoStego's own
 * codec and dumps their luma quantized coefficients + quant table, so a Python pipeline can
 * decompress them with the identical IDCT the simulator uses and compare against PIL's libjpeg decode.
 * Usage: JpegFileDump &lt;dir-of-jpg&gt; &lt;out.bin&gt; &lt;N&gt;  (files sorted numerically by basename)
 */
public final class JpegFileDump {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        String out = args[1];
        int n = Integer.parseInt(args[2]);
        File[] files = dir.listFiles((d, nm) -> nm.toLowerCase().endsWith(".jpg"));
        Arrays.sort(files, (a, b) -> {
            String na = a.getName().replaceAll("\\D", ""), nb = b.getName().replaceAll("\\D", "");
            if (!na.isEmpty() && !nb.isEmpty()) return Integer.compare(Integer.parseInt(na), Integer.parseInt(nb));
            return a.getName().compareTo(b.getName());
        });
        int count = Math.min(n, files.length);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            o.writeInt(count);
            for (int f = 0; f < count; f++) {
                JpegImage j = JpegCodec.decode(CommonUtil.fileToBytes(files[f]));
                int c = 0, bw = j.getBlocksWide(c), bh = j.getBlocksHigh(c);
                o.writeInt(bh); o.writeInt(bw);
                int[] quant = j.getQuantTable(c);
                for (int k = 0; k < 64; k++) o.writeInt(quant[k]);
                for (int br = 0; br < bh; br++)
                    for (int bc = 0; bc < bw; bc++) {
                        short[] blk = j.getBlock(c, br, bc);
                        for (int k = 0; k < 64; k++) o.writeShort(blk[k]);
                    }
            }
        }
        System.out.printf("decoded %d jpgs -> %s%n", count, out);
    }
}
