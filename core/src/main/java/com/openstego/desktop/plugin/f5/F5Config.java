/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.f5;

import com.openstego.desktop.OpenStegoConfig;

/**
 * Configuration holder for the F5 JPEG plugin. F5 embeds into an already-compressed JPEG cover and
 * re-encodes from that JPEG's own quantization tables, so &mdash; unlike the SI-UNIWARD plugin
 * &mdash; there is no output-quality knob. The plugin therefore carries only the common
 * {@link OpenStegoConfig} settings (password, compression, encryption); the password doubles as the
 * F5 PRNG key that seeds the coefficient permutation and the bit-pad.
 */
public class F5Config extends OpenStegoConfig {
    // No plugin-specific options: F5 reuses the cover JPEG's quantization tables on re-encode.
}
