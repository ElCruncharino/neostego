/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoException;

/**
 * Configuration holder for the SI-UNIWARD JPEG plugin. Besides the common {@link OpenStegoConfig}
 * settings it carries the JPEG output {@code quality} (1..100) used when the uncompressed precover is
 * transformed into quantized coefficients. Quality only affects embedding (it picks the quantization
 * tables and therefore the rounding-error side information); extraction reads the coefficients
 * straight from the stego JPEG and needs no configuration.
 * <p>
 * The STC payload width is not a knob: the message length and the cover's coefficient count together
 * fix it, so a shorter message automatically spreads over more of the cover (fewer, better-hidden
 * changes). Quality defaults from the environment ({@code NEOSTEGO_JPEG_QUALITY}) so the benchmark
 * harness can sweep it without code changes, while unit tests set it directly.
 */
public class JpegUniwardConfig extends OpenStegoConfig {

    /** Configuration key for the JPEG quality factor. */
    public static final String QUALITY = "quality";

    /** Configuration key for plain (non-side-informed) J-UNIWARD mode. */
    public static final String PLAIN_MODE = "plainMode";

    /** Default JPEG quality when neither the environment nor the caller overrides it. */
    private static final int DEFAULT_QUALITY = 90;

    private int quality = parseQuality();

    private boolean plainMode = "true".equalsIgnoreCase(System.getenv("NEOSTEGO_PLAIN_MODE"));

    private static int parseQuality() {
        String s = System.getenv("NEOSTEGO_JPEG_QUALITY");
        if (s != null) {
            try {
                int q = Integer.parseInt(s.trim());
                if (q >= 1 && q <= 100) {
                    return q;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return DEFAULT_QUALITY;
    }

    @Override
    protected void processConfigItem(String key, Object value) throws OpenStegoException {
        if (QUALITY.equals(key)) {
            if (value != null) {
                if (value instanceof Integer) {
                    setQuality((Integer) value);
                } else {
                    setQuality(Integer.parseInt(value.toString().trim()));
                }
            }
            return;
        }
        if (PLAIN_MODE.equals(key)) {
            if (value != null) {
                if (value instanceof Boolean) {
                    setPlainMode((Boolean) value);
                } else {
                    setPlainMode(Boolean.parseBoolean(value.toString().trim()));
                }
            }
            return;
        }
        super.processConfigItem(key, value);
    }

    /** @return the JPEG quality factor (1..100). */
    public int getQuality() {
        return this.quality;
    }

    /** @param quality the JPEG quality factor; clamped to 1..100. */
    public void setQuality(int quality) {
        this.quality = quality < 1 ? 1 : (quality > 100 ? 100 : quality);
    }

    /**
     * @return {@code true} for plain J-UNIWARD on an already-compressed JPEG cover (no side
     *         information); {@code false} (default) for Side-Informed UNIWARD from a precover.
     */
    public boolean isPlainMode() {
        return this.plainMode;
    }

    /** @param plainMode whether to embed into an existing JPEG cover without side information. */
    public void setPlainMode(boolean plainMode) {
        this.plainMode = plainMode;
    }
}
