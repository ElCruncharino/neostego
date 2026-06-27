/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.ProgressListener;
import java.util.List;

/**
 * Algorithm-agnostic extraction shared by every front-end (desktop GUI, Android, CLI). A stego file
 * records nothing about which plugin wrote it, so extraction tries the candidate data-hiding plugins
 * in turn until one decodes. Two rules keep the surfaced error meaningful:
 *
 * <ol>
 *   <li><b>Container gating</b> ({@link OpenStegoPlugin#canExtractFrom}): plugins that physically
 *       cannot read the container are skipped, so e.g. extracting from a PNG with a wrong password
 *       no longer reports the WAV plugin's "not a RIFF/WAVE container" error.
 *   <li><b>Invalid-password short-circuit</b>: once a plugin recognizes the container but rejects the
 *       password, that is the real error - stop trying the others and surface it immediately.
 * </ol>
 *
 * <p>The candidate plugin list is supplied by the caller rather than discovered here, because plugin
 * availability is platform policy: the desktop passes {@code PluginManager.getDataHidingPlugins()}
 * while Android passes an explicit, R8-friendly list of constructed plugins.
 */
public final class AutoExtractor {

    private AutoExtractor() {}

    /**
     * Attempts to extract hidden data by trying each candidate plugin that can read the container.
     *
     * @param stegoData  Stego file bytes
     * @param stegoName  Name of the stego file (used only for diagnostics / file-name recovery)
     * @param password   Extraction password (may be {@code null}/empty); never mutated - a clone is
     *                   handed to each attempt and wiped afterward
     * @param candidates Ordered candidate data-hiding plugins to try
     * @param listener   Optional progress listener wired into each attempt (may be {@code null})
     * @return Extracted output (element 0 is the file name, element 1 is the message bytes)
     * @throws OpenStegoException If no candidate could decode the file. An invalid-password failure is
     *                            propagated as-is; otherwise the last format error, or
     *                            {@link OpenStegoErrors#NO_VALID_PLUGIN} when nothing was applicable.
     */
    public static List<?> extract(
            byte[] stegoData,
            String stegoName,
            char[] password,
            List<OpenStegoPlugin<?>> candidates,
            ProgressListener listener)
            throws OpenStegoException {
        OpenStegoException last = null;
        for (OpenStegoPlugin<?> plugin : candidates) {
            if (!plugin.canExtractFrom(stegoData)) {
                continue;
            }
            plugin.resetConfig();
            OpenStegoConfig config = plugin.getConfig();
            config.setPassword(password == null ? null : password.clone());
            try {
                OpenStego stego = new OpenStego(plugin, config);
                if (listener != null) {
                    stego.setProgressListener(listener);
                }
                return stego.extractData(stegoData, stegoName);
            } catch (OpenStegoException e) {
                if (e.getErrorCode() == OpenStegoErrors.INVALID_PASSWORD) {
                    throw e; // right plugin matched the container, wrong password - no point trying others
                }
                last = e;
            } finally {
                config.clearPassword();
            }
        }
        if (last != null) {
            throw last;
        }
        throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.NO_VALID_PLUGIN);
    }
}
