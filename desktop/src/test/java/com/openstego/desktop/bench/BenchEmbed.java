/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.bench;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.PluginManager;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * Batch steganography embedder for benchmarking. Embeds the same fixed-size, per-image-deterministic
 * payload into every cover image of a directory with one algorithm, in a single JVM (so thousands of
 * images can be processed without per-image start-up cost). Output stego PNGs are written to the
 * target directory; images too small for the payload are skipped and counted.
 * <p>
 * Usage: {@code BenchEmbed <coverDir> <outDir> <algorithm> <payloadBytes> [password]}
 */
public final class BenchEmbed {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: BenchEmbed <coverDir> <outDir> <algorithm> <payloadBytes> [password]");
            System.exit(2);
        }
        File coverDir = new File(args[0]);
        File outDir = new File(args[1]);
        String algorithm = args[2];
        int payloadBytes = Integer.parseInt(args[3]);
        String password = args.length > 4 && !args[4].isEmpty() ? args[4] : null;

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
            throw new IllegalStateException("No cover images in: " + coverDir);
        }
        Arrays.sort(covers);

        int embedded = 0;
        int skipped = 0;
        long start = System.currentTimeMillis();
        for (File cover : covers) {
            byte[] coverBytes = CommonUtil.fileToBytes(cover);
            // Deterministic per-image payload, identical across algorithm runs for a fair comparison
            byte[] msg = new byte[payloadBytes];
            new Random(cover.getName().hashCode()).nextBytes(msg);
            try {
                byte[] stego = newStego(algorithm, password)
                        .embedData(msg, "p.bin", coverBytes, cover.getName(), cover.getName());
                File out = new File(outDir, baseName(cover.getName()) + ".png");
                Files.write(out.toPath(), stego);
                embedded++;
            } catch (OpenStegoException ex) {
                skipped++;
            }
            if ((embedded + skipped) % 200 == 0) {
                System.out.println("  " + (embedded + skipped) + "/" + covers.length + " processed");
            }
        }
        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println(algorithm + ": embedded=" + embedded + " skipped=" + skipped
                + " (" + secs + "s) -> " + outDir);
    }

    private static OpenStego newStego(String algorithm, String password) throws OpenStegoException {
        OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(algorithm);
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        }
        plugin.resetConfig();
        plugin.getConfig().setUseCompression(false);
        plugin.getConfig().setUseEncryption(password != null);
        if (password != null) {
            plugin.getConfig().setPassword(password);
        }
        return new OpenStego(plugin, plugin.getConfig());
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
