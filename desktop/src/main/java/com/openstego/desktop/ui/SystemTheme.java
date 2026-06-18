/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects the operating system's light/dark appearance preference so the UI can follow it.
 *
 * <p>Detection is best-effort and platform specific:
 * <ul>
 *   <li><b>Windows</b> &mdash; the {@code AppsUseLightTheme} value under the Personalize registry key.</li>
 *   <li><b>macOS</b> &mdash; the global {@code AppleInterfaceStyle} default (present and equal to
 *       {@code Dark} only in dark mode).</li>
 *   <li><b>Linux / BSD</b> &mdash; the cross-desktop XDG Desktop Portal
 *       ({@code org.freedesktop.appearance color-scheme}, honoured by KDE Plasma, GNOME and others),
 *       falling back to GNOME's {@code gsettings} {@code color-scheme} and then the GTK theme name.</li>
 * </ul>
 *
 * <p>Every probe is sandboxed and time-bounded; anything unexpected (no desktop environment, a bare
 * window manager with no system preference, a missing tool) falls back to {@link UITheme#LIGHT}.
 */
public final class SystemTheme {
    private static final Logger logger = Logger.getLogger(SystemTheme.class.getName());

    /** Maximum time to wait for any single detection sub-process. */
    private static final long PROBE_TIMEOUT_SECONDS = 3;

    private SystemTheme() {
        // Static utility
    }

    /**
     * Detects the current OS appearance.
     *
     * @return {@link UITheme#DARK} when the OS is in dark mode, otherwise {@link UITheme#LIGHT}
     *         (also the fallback when no preference can be determined)
     */
    public static String detect() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return detectWindows();
            }
            if (os.contains("mac")) {
                return detectMac();
            }
            return detectLinux();
        } catch (Exception ex) {
            logger.log(Level.FINE, "OS theme detection failed; defaulting to light", ex);
            return UITheme.LIGHT;
        }
    }

    private static String detectWindows() {
        // 0 = dark, 1 = light. Absent key => assume light.
        String out = run(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "AppsUseLightTheme");
        if (out != null) {
            for (String line : out.split("\\R")) {
                line = line.trim();
                if (line.contains("AppsUseLightTheme")) {
                    // e.g. "AppsUseLightTheme    REG_DWORD    0x0"
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.endsWith("0x0")) {
                        return UITheme.DARK;
                    }
                    if (lower.endsWith("0x1")) {
                        return UITheme.LIGHT;
                    }
                }
            }
        }
        return UITheme.LIGHT;
    }

    private static String detectMac() {
        // The default exists (value "Dark") only in dark mode; in light mode the command fails.
        String out = run("defaults", "read", "-g", "AppleInterfaceStyle");
        if (out != null && out.toLowerCase(Locale.ROOT).contains("dark")) {
            return UITheme.DARK;
        }
        return UITheme.LIGHT;
    }

    private static String detectLinux() {
        // 1. XDG Desktop Portal: the modern cross-desktop standard (KDE Plasma >= 5.24, GNOME, ...).
        //    color-scheme: 0 = no preference, 1 = prefer dark, 2 = prefer light.
        String portal = run(
                "gdbus",
                "call",
                "--session",
                "--dest",
                "org.freedesktop.portal.Desktop",
                "--object-path",
                "/org/freedesktop/portal/desktop",
                "--method",
                "org.freedesktop.portal.Settings.Read",
                "org.freedesktop.appearance",
                "color-scheme");
        if (portal != null) {
            // Reply looks like: (<<uint32 1>>,)
            String digits = portal.replaceAll("[^0-9]", "");
            if (digits.contains("1")) {
                return UITheme.DARK;
            }
            if (digits.contains("2")) {
                return UITheme.LIGHT;
            }
            // "0" (no preference) falls through to the next probe
        }

        // 2. GNOME color-scheme setting.
        String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (scheme != null && scheme.toLowerCase(Locale.ROOT).contains("dark")) {
            return UITheme.DARK;
        }

        // 3. Last resort: infer from the GTK theme name (e.g. "Adwaita-dark").
        String gtkTheme = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
        if (gtkTheme != null && gtkTheme.toLowerCase(Locale.ROOT).contains("dark")) {
            return UITheme.DARK;
        }

        return UITheme.LIGHT;
    }

    /**
     * Runs an external command and returns its trimmed standard output, or {@code null} if the
     * command cannot be run, times out, or exits with a non-zero status.
     */
    private static String run(String... command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            // Tool not installed, not on PATH, interrupted, etc. Treat as "unknown".
            logger.log(Level.FINEST, "Theme probe command failed: " + String.join(" ", command), ex);
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
