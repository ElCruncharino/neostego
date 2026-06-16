/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.bench;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
import com.openstego.desktop.image.jpeg.JpegImage;
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * Batch JPEG benchmark generator for steganalysis. Produces matched cover/stego JPEG pairs from a
 * directory of uncompressed precovers (PNG/BMP), both compressed by NeoStego's own codec at the same
 * quality so a detector sees the embedding change and nothing else (no compressor mismatch to learn).
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@code cover} &mdash; transcode each precover to a clean JPEG via {@link JpegCodec#fromPrecover}
 *       + {@link JpegCodec#encode} (no embedding). This is the honest control.</li>
 *   <li>{@code stego} &mdash; embed a deterministic per-image payload with a JPEG plugin (default
 *       {@code JpegUniward}) at the same quality.</li>
 * </ul>
 * Filenames are preserved (extension changed to {@code .jpg}) so {@code cover/<name>.jpg} pairs with
 * {@code stego/<name>.jpg}.
 * <p>
 * Usage: {@code BenchJpeg <mode> <coverDir> <outDir> <quality> [payloadBytes] [algo] [password]}
 */
public final class BenchJpeg {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println(
                    "Usage: BenchJpeg <cover|stego> <coverDir> <outDir> <quality> [payloadBytes] [algo] [password]");
            System.exit(2);
        }
        String mode = args[0];
        File coverDir = new File(args[1]);
        File outDir = new File(args[2]);
        int quality = Integer.parseInt(args[3]);
        int payloadBytes = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        String algo = args.length > 5 && !args[5].isEmpty() ? args[5] : "JpegUniward";
        String password = args.length > 6 && !args[6].isEmpty() ? args[6] : null;

        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();

        if ("diag".equalsIgnoreCase(mode)) {
            diag(coverDir, outDir, quality, payloadBytes, algo, password);
            return;
        }

        if ("ideal".equalsIgnoreCase(mode)) {
            // optional 6th arg (reusing the algo slot) = cost-blind header-emulation changes
            int blind = 0;
            if (args.length > 5 && !args[5].isEmpty()) {
                try {
                    blind = Integer.parseInt(args[5]);
                } catch (NumberFormatException ignore) {
                    blind = 0;
                }
            }
            ideal(coverDir, outDir, quality, payloadBytes, blind);
            return;
        }

        boolean stego = "stego".equalsIgnoreCase(mode);
        if (!stego && !"cover".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("mode must be 'cover', 'stego', 'ideal' or 'diag'");
        }

        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + outDir);
        }

        File[] covers = coverDir.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".png") || l.endsWith(".bmp");
        });
        if (covers == null || covers.length == 0) {
            throw new IllegalStateException("No precover images in: " + coverDir);
        }
        Arrays.sort(covers);

        int done = 0;
        int skipped = 0;
        long start = System.currentTimeMillis();
        for (File cover : covers) {
            byte[] coverBytes = CommonUtil.fileToBytes(cover);
            try {
                byte[] jpeg;
                if (stego) {
                    byte[] msg = new byte[payloadBytes];
                    new Random(cover.getName().hashCode()).nextBytes(msg);
                    jpeg = newStego(algo, quality, password)
                            .embedData(msg, "p.bin", coverBytes, cover.getName(), cover.getName());
                } else {
                    PixelImage precover = ImageCodecRegistry.get().decode(coverBytes, cover.getName());
                    jpeg = JpegCodec.encode(JpegCodec.fromPrecover(precover, quality));
                }
                File out = new File(outDir, baseName(cover.getName()) + ".jpg");
                Files.write(out.toPath(), jpeg);
                done++;
            } catch (OpenStegoException ex) {
                skipped++;
            }
            if ((done + skipped) % 200 == 0) {
                System.out.println("  " + (done + skipped) + "/" + covers.length + " processed");
            }
        }
        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println(mode + "/" + algo + " q" + quality + ": done=" + done + " skipped=" + skipped
                + " (" + secs + "s) -> " + outDir);
    }

    /**
     * Ideal mode: embed exactly {@code payloadBytes} of message with the <em>optimal</em> binary
     * embedding simulator on the true SI-UNIWARD costs &mdash; no bootstrap header, no STC coder. This
     * is the rate&ndash;distortion bound for the distortion function: it changes each AC coefficient
     * independently with probability {@code 1/(1+exp(lambda*rho))} (lambda tuned by bisection so the
     * total coding entropy equals the payload), realising each change as the side-info {@code sign(e)}.
     * Compared head-to-head with {@code stego} mode through the same JPEG codec and the same DCTR
     * evaluator, it isolates how much detectability the coder + header add over the bound.
     */
    private static void ideal(File coverDir, File outDir, int quality, int payloadBytes, int blindChanges)
            throws Exception {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + outDir);
        }
        File[] covers = coverDir.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".png") || l.endsWith(".bmp");
        });
        if (covers == null || covers.length == 0) {
            throw new IllegalStateException("No precover images in: " + coverDir);
        }
        Arrays.sort(covers);
        int done = 0;
        long sumChg = 0, sumNz = 0;
        for (File cover : covers) {
            byte[] coverBytes = CommonUtil.fileToBytes(cover);
            PixelImage precover = ImageCodecRegistry.get().decode(coverBytes, cover.getName());
            JpegImage jpg = JpegCodec.fromPrecover(precover, quality);

            long[] stat = com.openstego.desktop.plugin.jpeguniward.IdealSim.embed(
                    jpg, payloadBytes, cover.getName().hashCode() * 0x9E3779B97F4A7C15L, blindChanges);
            sumChg += stat[0];
            sumNz += stat[1];

            byte[] jpeg = JpegCodec.encode(jpg);
            Files.write(new File(outDir, baseName(cover.getName()) + ".jpg").toPath(), jpeg);
            done++;
        }
        double bpnz = (double) payloadBytes * 8 * done / Math.max(1, sumNz);
        System.out.printf("ideal q%d payload=%dB: done=%d  mean changes=%.0f  bpnzAC=%.4f -> %s%n",
                quality, payloadBytes, done, (double) sumChg / done, bpnz, outDir);
    }

    /**
     * Diagnostic mode: for a sample of precovers, report the embedding change profile that a JPEG
     * steganalyzer keys on -- true payload in bits per non-zero AC (bpnzAC), the number of coefficient
     * changes, and the change breakdown (zeros driven to +/-1 vs non-zeros nudged). Flooding zeros is
     * the most DCTR-detectable failure mode, so the zero->nonzero share is the headline number.
     */
    private static void diag(File coverDir, File outDir, int quality, int payloadBytes, String algo,
            String password) throws Exception {
        int limit = outDir != null ? safeParseLimit(outDir.getName()) : 60;
        File[] covers = coverDir.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".png") || l.endsWith(".bmp");
        });
        if (covers == null || covers.length == 0) {
            throw new IllegalStateException("No precover images in: " + coverDir);
        }
        Arrays.sort(covers);
        int count = Math.min(limit, covers.length);

        long sumTotalAc = 0, sumNzAc = 0, sumChanges = 0;
        long sumZeroToNz = 0, sumNzToZero = 0, sumNzToNz = 0;
        long sumMsgBits = 0;
        int n = 0;
        for (int f = 0; f < count; f++) {
            File cover = covers[f];
            byte[] coverBytes = CommonUtil.fileToBytes(cover);
            PixelImage precover = ImageCodecRegistry.get().decode(coverBytes, cover.getName());
            JpegImage jc = JpegCodec.fromPrecover(precover, quality);

            long totalAc = 0, nzAc = 0;
            int comps = jc.getComponentCount();
            for (int c = 0; c < comps; c++) {
                for (int br = 0; br < jc.getBlocksHigh(c); br++) {
                    for (int bc = 0; bc < jc.getBlocksWide(c); bc++) {
                        short[] blk = jc.getBlock(c, br, bc);
                        for (int k = 1; k < 64; k++) {
                            totalAc++;
                            if (blk[k] != 0) {
                                nzAc++;
                            }
                        }
                    }
                }
            }

            byte[] msg = new byte[payloadBytes];
            new Random(cover.getName().hashCode()).nextBytes(msg);
            byte[] stegoBytes;
            try {
                stegoBytes = newStego(algo, quality, password)
                        .embedData(msg, "p.bin", coverBytes, cover.getName(), cover.getName());
            } catch (OpenStegoException ex) {
                continue;
            }
            JpegImage js = JpegCodec.decode(stegoBytes);

            long changes = 0, z2nz = 0, nz2z = 0, nz2nz = 0;
            for (int c = 0; c < comps; c++) {
                for (int br = 0; br < jc.getBlocksHigh(c); br++) {
                    for (int bc = 0; bc < jc.getBlocksWide(c); bc++) {
                        short[] a = jc.getBlock(c, br, bc);
                        short[] b = js.getBlock(c, br, bc);
                        for (int k = 1; k < 64; k++) {
                            if (a[k] != b[k]) {
                                changes++;
                                if (a[k] == 0) {
                                    z2nz++;
                                } else if (b[k] == 0) {
                                    nz2z++;
                                } else {
                                    nz2nz++;
                                }
                            }
                        }
                    }
                }
            }
            sumTotalAc += totalAc;
            sumNzAc += nzAc;
            sumChanges += changes;
            sumZeroToNz += z2nz;
            sumNzToZero += nz2z;
            sumNzToNz += nz2nz;
            sumMsgBits += (long) payloadBytes * 8;
            n++;
        }
        if (n == 0) {
            System.out.println("diag: no images processed");
            return;
        }
        double bpnzac = (double) sumMsgBits / sumNzAc;
        double bpac = (double) sumMsgBits / sumTotalAc;
        double changeRateNz = (double) sumChanges / sumNzAc;
        double effBitsPerChange = (double) sumMsgBits / Math.max(1, sumChanges);
        System.out.printf("=== diag %s q%d payload=%dB  (n=%d images) ===%n", algo, quality, payloadBytes, n);
        System.out.printf("mean total AC slots : %.0f%n", (double) sumTotalAc / n);
        System.out.printf("mean non-zero AC    : %.0f  (%.1f%% of AC)%n",
                (double) sumNzAc / n, 100.0 * sumNzAc / sumTotalAc);
        System.out.printf("payload             : %.4f bpnzAC   |  %.4f bpAC%n", bpnzac, bpac);
        System.out.printf("mean changes        : %.0f  (%.4f per nzAC, %.2f efficiency bits/change)%n",
                (double) sumChanges / n, changeRateNz, effBitsPerChange);
        System.out.printf("change breakdown    : zero->+-1 %.1f%%   nz->zero %.1f%%   nz->nz %.1f%%%n",
                100.0 * sumZeroToNz / sumChanges, 100.0 * sumNzToZero / sumChanges,
                100.0 * sumNzToNz / sumChanges);
    }

    private static int safeParseLimit(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return 60;
        }
    }

    private static OpenStego newStego(String algo, int quality, String password) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(algo);
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown algorithm: " + algo);
        }
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(password != null);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        if (plugin.getConfig() instanceof JpegUniwardConfig) {
            ((JpegUniwardConfig) plugin.getConfig()).setQuality(quality);
        }
        return new OpenStego(plugin, plugin.getConfig());
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
