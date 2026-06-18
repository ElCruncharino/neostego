/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) 2017 Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */
package com.openstego.desktop.util;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

/**
 * User preferences manager
 */
public class UserPreferences {
    private static final String PREF_FILENAME = "preferences.properties";
    private static final String DEFAULT_PREF_FILENAME = "preferences.default.properties";
    // Older versions stored preferences as "neostego.cfg". On Windows that name collides
    // (case-insensitively) with the jpackage launcher's per-user config probe
    // (%APPDATA%\NeoStego\NeoStego.cfg), which makes the launcher read this file as its
    // launch configuration and fail with "Failed to launch JVM". Migrate away from it.
    private static final String LEGACY_PREF_FILENAME = "neostego.cfg";
    private static Properties prefs = null;
    private static Path prefFilePath = null;

    /**
     * Protected constructor. Expose only static methods
     */
    protected UserPreferences() {
        // Do nothing
    }

    /**
     * Initialize the preferences
     *
     * @throws OpenStegoException Processing issues
     */
    public static void init() throws OpenStegoException {
        if (prefs != null) {
            return;
        }

        prefs = new Properties();

        try {
            // Create the platform-appropriate config directory if it does not exist
            Path configPath = resolveConfigDir();
            if (Files.notExists(configPath)) {
                Files.createDirectories(configPath);
            }

            Path prefFile = configPath.resolve(PREF_FILENAME);
            prefFilePath = prefFile;

            // One-time migration: remove the legacy "neostego.cfg" file, which on Windows is
            // mistaken for the jpackage launcher config and prevents the app from starting.
            // Preserve the user's settings by promoting it to the new filename when possible.
            Path legacyFile = configPath.resolve(LEGACY_PREF_FILENAME);
            if (Files.exists(legacyFile)) {
                if (Files.notExists(prefFile)) {
                    Files.move(legacyFile, prefFile, REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(legacyFile);
                }
            }

            // Create preference file if it does not exist
            if (Files.notExists(prefFile)) {
                // Seed from the default template bundled with the application
                try (InputStream tmplIS = UserPreferences.class.getResourceAsStream("/" + DEFAULT_PREF_FILENAME)) {
                    assert tmplIS != null;
                    Files.copy(tmplIS, prefFile, REPLACE_EXISTING);
                }
            }

            try (InputStream prefFileIS = Files.newInputStream(prefFile)) {
                prefs.load(prefFileIS);
            }
        } catch (IOException e) {
            throw new OpenStegoException(e);
        }
    }

    /**
     * Resolves the per-user configuration directory following each platform's convention:
     * {@code %APPDATA%} (Roaming) on Windows, {@code ~/Library/Application Support} on macOS, and
     * the XDG base directory ({@code $XDG_CONFIG_HOME}, defaulting to {@code ~/.config}) elsewhere.
     *
     * @return the directory in which the preferences file should be stored
     */
    private static Path resolveConfigDir() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.trim().isEmpty())
                    ? Paths.get(appData)
                    : Paths.get(userHome, "AppData", "Roaming");
            return base.resolve("NeoStego");
        }

        if (os.contains("mac")) {
            return Paths.get(userHome, "Library", "Application Support", "NeoStego");
        }

        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.trim().isEmpty()) ? Paths.get(xdg) : Paths.get(userHome, ".config");
        return base.resolve("neostego");
    }

    /**
     * Returns the user preference in form of string
     *
     * @param key Preference key
     * @return value
     */
    public static String getString(String key) {
        // Preferences may not have been initialized (e.g. headless library use or unit tests); treat that
        // the same as "no value set" so callers fall back to their defaults instead of throwing.
        if (prefs == null) {
            return null;
        }
        String val = prefs.getProperty(key);
        if (val == null) {
            return null;
        }
        return val.trim();
    }

    /**
     * Sets a user preference value in memory. Call {@link #save()} to persist it to disk.
     *
     * @param key   Preference key
     * @param value Preference value
     */
    public static void put(String key, String value) {
        if (prefs == null) {
            return;
        }
        prefs.setProperty(key, value);
    }

    /**
     * Persists the current preferences to the user's preference file.
     *
     * @throws OpenStegoException Processing issues
     */
    public static void save() throws OpenStegoException {
        if (prefs == null || prefFilePath == null) {
            return;
        }
        try (OutputStream prefFileOS = Files.newOutputStream(prefFilePath)) {
            prefs.store(prefFileOS, "OpenStego user preferences");
        } catch (IOException e) {
            throw new OpenStegoException(e);
        }
    }

    /**
     * Returns the user preference in form of integer
     *
     * @param key Preference key
     * @return value
     * @throws OpenStegoException Processing issues
     */
    @SuppressWarnings("unused")
    public static Integer getInteger(String key) throws OpenStegoException {
        String val = getString(key);
        if (val == null) {
            return null;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.USERPREF_INVALID_INT, key);
        }
    }

    /**
     * Returns the user preference in form of float
     *
     * @param key Preference key
     * @return value
     * @throws OpenStegoException Processing issues
     */
    public static Float getFloat(String key) throws OpenStegoException {
        String val = getString(key);
        if (val == null) {
            return null;
        }
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.USERPREF_INVALID_FLOAT, key);
        }
    }

    /**
     * Returns the user preference in form of boolean
     *
     * @param key Preference key
     * @return value
     * @throws OpenStegoException Processing issues
     */
    @SuppressWarnings("unused")
    public static Boolean getBoolean(String key) throws OpenStegoException {
        String val = getString(key);
        if (val == null) {
            return null;
        }
        val = val.toLowerCase();
        if ("t".equals(val) || "true".equals(val) || "y".equals(val) || "yes".equals(val) || "1".equals(val)) {
            return true;
        } else if ("f".equals(val) || "false".equals(val) || "n".equals(val) || "no".equals(val) || "0".equals(val)) {
            return false;
        } else {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.USERPREF_INVALID_BOOL, key);
        }
    }
}
