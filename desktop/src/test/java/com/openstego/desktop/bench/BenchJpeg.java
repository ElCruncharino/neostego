/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.bench;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.image.ImageCodecRegistry;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.image.jpeg.JpegCodec;
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

        boolean stego = "stego".equalsIgnoreCase(mode);
        if (!stego && !"cover".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("mode must be 'cover' or 'stego'");
        }

        Class.forName(OpenStego.class.getName());
        PluginManager.loadPlugins();

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
