/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.openstego.desktop.util.UserPreferences;
import javax.swing.UIManager;

/**
 * Manages the application look-and-feel using FlatLaf, providing a light and a dark theme. The
 * selected theme is persisted via {@link UserPreferences} so that it is remembered across runs.
 */
public class UITheme {
    /**
     * Preference key under which the selected theme is stored
     */
    public static final String PREF_KEY = "gui.theme";

    /**
     * Identifier for the light theme
     */
    public static final String LIGHT = "light";

    /**
     * Identifier for the dark theme
     */
    public static final String DARK = "dark";

    /**
     * Identifier for the "follow the operating system" theme mode. When selected, the concrete
     * light or dark theme is resolved from the OS appearance via {@link SystemTheme}.
     */
    public static final String SYSTEM = "system";

    /**
     * Protected constructor. Expose only static methods
     */
    protected UITheme() {
        // Do nothing
    }

    /**
     * Returns the currently configured theme mode, defaulting to {@link #SYSTEM} when unset.
     *
     * @return Theme mode ({@link #LIGHT}, {@link #DARK} or {@link #SYSTEM})
     */
    public static String current() {
        String theme = UserPreferences.getString(PREF_KEY);
        if (theme == null || theme.trim().isEmpty()) {
            return SYSTEM;
        }
        theme = theme.trim();
        if (DARK.equalsIgnoreCase(theme)) {
            return DARK;
        }
        if (LIGHT.equalsIgnoreCase(theme)) {
            return LIGHT;
        }
        return SYSTEM;
    }

    /**
     * Resolves a theme mode to a concrete theme that FlatLaf can install. {@link #SYSTEM} is mapped
     * to the detected OS appearance; {@link #LIGHT} and {@link #DARK} are returned as-is.
     *
     * @param mode Theme mode ({@link #LIGHT}, {@link #DARK} or {@link #SYSTEM})
     * @return Concrete theme ({@link #LIGHT} or {@link #DARK})
     */
    public static String resolve(String mode) {
        if (SYSTEM.equals(mode)) {
            return SystemTheme.detect();
        }
        return DARK.equals(mode) ? DARK : LIGHT;
    }

    /**
     * Installs the look-and-feel for the given theme mode as the active one. {@link #SYSTEM} follows
     * the OS appearance. If FlatLaf cannot be installed for any reason, this falls back to the
     * platform system look-and-feel.
     *
     * @param mode Theme mode ({@link #LIGHT}, {@link #DARK} or {@link #SYSTEM})
     */
    public static void install(String mode) {
        // On macOS, make the window chrome (title bar) follow the OS appearance too.
        try {
            if (System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("mac")) {
                System.setProperty("apple.awt.application.appearance", "system");
            }
        } catch (Exception ignore) {
            // Non-fatal: only affects native title-bar tinting
        }
        try {
            if (DARK.equals(resolve(mode))) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            // Show an integrated reveal (eye) button inside all password fields
            UIManager.put("PasswordField.showRevealButton", true);
        } catch (Exception ex) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignore) {
                // Give up and let Swing use its default look-and-feel
            }
        }
    }

    /**
     * Switches the active theme mode at runtime, repaints all open windows, and persists the choice.
     *
     * @param mode Theme mode ({@link #LIGHT}, {@link #DARK} or {@link #SYSTEM})
     */
    public static void switchTo(String mode) {
        install(mode);
        FlatLaf.updateUI();
        try {
            UserPreferences.put(PREF_KEY, mode);
            UserPreferences.save();
        } catch (Exception ex) {
            // Non-fatal: the theme is applied for this session even if it cannot be saved
        }
    }
}
