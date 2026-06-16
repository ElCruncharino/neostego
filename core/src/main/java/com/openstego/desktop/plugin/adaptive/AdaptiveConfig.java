/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoConfig;

/**
 * Configuration holder for the content-adaptive (HILL+STC) plugin. Besides the common
 * {@link OpenStegoConfig} settings the algorithm derives its parameters (payload width, costs) from
 * the cover image at embed time.
 * <p>
 * Two knobs control the CMD (Clustering Modification Directions) v2 embedding, which clusters the
 * <em>selected</em> pixels across 2&times;2 sub-lattices so changes form neighbour-aligned groups.
 * v2 is the default: on the SRM rich-model and the SRM-CNN it lowered detection AUC versus the v1
 * single-STC path at both 0.41 and 0.20 bpp. Old v1 stego files still decode (the decoder branches
 * on a width-field sentinel). The knobs default from the environment so the benchmark harness can
 * sweep them without code changes, while unit tests set them directly:
 * <ul>
 *   <li>{@code cmd} (env {@code NEOSTEGO_ADAPTIVE_CMD}: {@code 1}/{@code 0}) &mdash; emit v2 (CMD)
 *       stego; default on. Set the env to {@code 0} to force the legacy v1 single-STC path;</li>
 *   <li>{@code cmdMu} (env {@code NEOSTEGO_ADAPTIVE_MU}, default 3.0) &mdash; the cost-reduction
 *       factor applied on the neighbour-aligned side; the {@code MU=3} sweet spot beat both
 *       {@code MU=1} (no selection bias) and {@code MU=9} (over-clustering) in benchmarking.</li>
 * </ul>
 */
public class AdaptiveConfig extends OpenStegoConfig {

    private boolean cmd = parseCmd();
    private double cmdMu = parseMu();

    private static boolean parseCmd() {
        String s = System.getenv("NEOSTEGO_ADAPTIVE_CMD");
        if (s != null) {
            return "1".equals(s.trim());
        }
        return true;
    }

    private static double parseMu() {
        String s = System.getenv("NEOSTEGO_ADAPTIVE_MU");
        if (s != null) {
            try {
                double d = Double.parseDouble(s.trim());
                if (d >= 1.0) {
                    return d;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 3.0;
    }

    /** @return whether the CMD (v2) embedding path is enabled. */
    public boolean isCmd() {
        return this.cmd;
    }

    /** @param cmd enable/disable the CMD (v2) embedding path. */
    public void setCmd(boolean cmd) {
        this.cmd = cmd;
    }

    /** @return the CMD alignment cost-reduction factor (&ge;1; 1 = no selection bias). */
    public double getCmdMu() {
        return this.cmdMu;
    }

    /** @param cmdMu the CMD alignment cost-reduction factor; clamped to &ge;1. */
    public void setCmdMu(double cmdMu) {
        this.cmdMu = cmdMu < 1.0 ? 1.0 : cmdMu;
    }
}
