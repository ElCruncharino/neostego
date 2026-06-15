/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoConfig;

/**
 * Configuration holder for the content-adaptive (HILL+STC) plugin. It carries no extra options
 * beyond the common {@link OpenStegoConfig} settings; the algorithm derives its parameters (payload
 * width, costs) from the cover image at embed time.
 */
public class AdaptiveConfig extends OpenStegoConfig {
}
