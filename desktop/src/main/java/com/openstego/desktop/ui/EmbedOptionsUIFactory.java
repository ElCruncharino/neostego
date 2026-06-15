/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import com.openstego.desktop.plugin.adaptive.AdaptiveEmbedOptionsUI;
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardEmbedOptionsUI;
import com.openstego.desktop.plugin.lsb.LSBEmbedOptionsUI;

/**
 * Maps a data-hiding plugin name to the Swing options panel that exposes its
 * tunable flags.
 * <p>
 * The mapping lives in the desktop module (not core) so that the core plugin
 * interface stays free of any Swing dependency.
 */
public final class EmbedOptionsUIFactory {

    private EmbedOptionsUIFactory() {
        // Static factory; no instances
    }

    /**
     * Returns the options panel for the given plugin name, or {@code null} if the
     * plugin exposes no tunable options.
     *
     * @param pluginName Name of the data-hiding plugin (as returned by {@code getName()})
     * @param stegoUI    Reference to the parent UI frame
     * @return A new {@link PluginEmbedOptionsUI} instance, or {@code null}
     */
    public static PluginEmbedOptionsUI create(String pluginName, OpenStegoFrame stegoUI) {
        if (pluginName == null) {
            return null;
        }
        switch (pluginName) {
            case "Adaptive":
                return new AdaptiveEmbedOptionsUI(stegoUI);
            case "JpegUniward":
                return new JpegUniwardEmbedOptionsUI(stegoUI);
            case "LSB":
            case "RandomLSB":
            case "RandomLSBMatch":
                return new LSBEmbedOptionsUI(stegoUI);
            default:
                return null;
        }
    }
}
