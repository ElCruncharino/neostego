/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.util.CommonUtil;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Diagnostic: dumps the luma precover plane, quant table, rounding errors and the (non-SI) UNIWARD
 * base cost map for one precover, so an independent reference implementation can verify the cost
 * ranking. Binary little-endian: [int H][int W] plane(H*W double); [int 64] quant(double);
 * [int nb] cost(nb*64 double); err(nb*64 double). Usage: CostDump &lt;precover.png&gt; &lt;out.bin&gt; [quality]
 */
public final class CostDump {
    public static void main(String[] args) throws Exception {
        Class.forName(OpenStego.class.getName());
        File pre = new File(args[0]);
        String out = args[1];
        int q = args.length > 2 ? Integer.parseInt(args[2]) : 90;
        PixelImage precover = ImageCodecRegistry.get().decode(CommonUtil.fileToBytes(pre), pre.getName());
        JpegImage jpg = JpegCodec.fromPrecover(precover, q);
        int c = 0; // luma
        double[][] plane = jpg.getPlane(c);
        int H = plane.length, W = plane[0].length;
        int bw = jpg.getBlocksWide(c), bh = jpg.getBlocksHigh(c);
        int[] quant = jpg.getQuantTable(c);
        double[][] cost = UniwardCost.compute(plane, H, W, bw, bh, quant);
        int nb = bw * bh;
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(out))) {
            o.writeInt(H); o.writeInt(W);
            for (int r = 0; r < H; r++) for (int cc = 0; cc < W; cc++) o.writeDouble(plane[r][cc]);
            o.writeInt(64);
            for (int k = 0; k < 64; k++) o.writeDouble(quant[k]);
            o.writeInt(nb); o.writeInt(bw); o.writeInt(bh);
            for (int b = 0; b < nb; b++) for (int k = 0; k < 64; k++) o.writeDouble(cost[b][k]);
            for (int br = 0; br < bh; br++) for (int bc = 0; bc < bw; bc++) {
                double[] e = jpg.getRounding(c, br, bc);
                for (int k = 0; k < 64; k++) o.writeDouble(e[k]);
            }
        }
        System.out.printf("dumped H=%d W=%d blocks=%dx%d -> %s%n", H, W, bw, bh, out);
    }
}
