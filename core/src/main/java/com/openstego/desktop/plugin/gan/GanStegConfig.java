/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.gan;

import com.openstego.desktop.OpenStegoConfig;

/**
 * Configuration holder for the experimental GAN-based steganography plugin. No plugin-specific
 * options beyond the common {@link OpenStegoConfig} settings (password, compression, encryption) --
 * the network architecture and tiling/error-correction parameters are fixed by the shipped model.
 */
public class GanStegConfig extends OpenStegoConfig {
    // No plugin-specific options.
}
