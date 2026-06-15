/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoConfig;

/**
 * Configuration holder for the content-adaptive (HILL+STC) plugin. Besides the common
 * {@link OpenStegoConfig} settings the algorithm derives its parameters (payload width, costs) from
 * the cover image at embed time.
 * <p>
 * Two optional knobs control the experimental CMD (Clustering Modification Directions) v2 embedding,
 * which clusters the <em>selected</em> pixels across 2&times;2 sub-lattices so changes form
 * neighbour-aligned groups. They default from the environment so the benchmark harness can toggle
 * them without code changes, while unit tests set them directly:
 * <ul>
 *   <li>{@code cmd} (env {@code NEOSTEGO_ADAPTIVE_CMD=1}) &mdash; emit v2 (CMD) stego instead of v1;</li>
 *   <li>{@code cmdMu} (env {@code NEOSTEGO_ADAPTIVE_MU}, default 9.0) &mdash; the cost-reduction factor
 *       applied on the neighbour-aligned side; {@code MU=1} disables the selection bias.</li>
 * </ul>
 * v2 is gated and off by default, so the shipped behaviour is unchanged and old files keep decoding.
 */
public class AdaptiveConfig extends OpenStegoConfig {

    private boolean cmd = "1".equals(System.getenv("NEOSTEGO_ADAPTIVE_CMD"));
    private double cmdMu = parseMu();

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
        return 9.0;
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
