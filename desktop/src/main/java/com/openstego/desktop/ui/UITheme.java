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
     * Protected constructor. Expose only static methods
     */
    protected UITheme() {
        // Do nothing
    }

    /**
     * Returns the currently configured theme, defaulting to {@link #LIGHT} when unset or unknown.
     *
     * @return Theme identifier ({@link #LIGHT} or {@link #DARK})
     */
    public static String current() {
        String theme = UserPreferences.getString(PREF_KEY);
        if (theme == null || theme.trim().isEmpty()) {
            return LIGHT;
        }
        return DARK.equalsIgnoreCase(theme.trim()) ? DARK : LIGHT;
    }

    /**
     * Installs the given FlatLaf theme as the active look-and-feel. If FlatLaf cannot be installed
     * for any reason, this falls back to the platform system look-and-feel.
     *
     * @param theme Theme identifier ({@link #LIGHT} or {@link #DARK})
     */
    public static void install(String theme) {
        try {
            if (DARK.equals(theme)) {
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
     * Switches the active theme at runtime, repaints all open windows, and persists the choice.
     *
     * @param theme Theme identifier ({@link #LIGHT} or {@link #DARK})
     */
    public static void switchTo(String theme) {
        install(theme);
        FlatLaf.updateUI();
        try {
            UserPreferences.put(PREF_KEY, theme);
            UserPreferences.save();
        } catch (Exception ex) {
            // Non-fatal: the theme is applied for this session even if it cannot be saved
        }
    }
}
