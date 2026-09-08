/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import com.openstego.desktop.util.UserPreferences;
import java.util.Locale;

/**
 * Manages the application UI language, independent of the JVM/OS locale. The selected language is
 * persisted via {@link UserPreferences} so that it is remembered across runs.
 */
public class UILocale {
    /**
     * Preference key under which the selected language is stored
     */
    public static final String PREF_KEY = "gui.language";

    /**
     * Identifier for the "follow the operating system" language mode
     */
    public static final String SYSTEM = "system";

    /**
     * Identifier for English
     */
    public static final String EN = "en";

    /**
     * Identifier for Chinese
     */
    public static final String ZH = "zh";

    /**
     * Identifier for Japanese
     */
    public static final String JA = "ja";

    /**
     * The JVM's default locale as captured at class-load time, before {@link #install} ever changes
     * it. Used to restore the OS-provided locale when {@link #SYSTEM} is selected.
     */
    private static final Locale SYSTEM_LOCALE = Locale.getDefault();

    /**
     * Protected constructor. Expose only static methods
     */
    protected UILocale() {
        // Do nothing
    }

    /**
     * Returns the currently configured language mode, defaulting to {@link #SYSTEM} when unset.
     *
     * @return Language mode ({@link #SYSTEM}, {@link #EN}, {@link #ZH} or {@link #JA})
     */
    public static String current() {
        String lang = UserPreferences.getString(PREF_KEY);
        if (lang == null || lang.trim().isEmpty()) {
            return SYSTEM;
        }
        lang = lang.trim();
        if (EN.equalsIgnoreCase(lang) || ZH.equalsIgnoreCase(lang) || JA.equalsIgnoreCase(lang)) {
            return lang.toLowerCase(Locale.ROOT);
        }
        return SYSTEM;
    }

    /**
     * Installs the given language mode as the JVM default locale. Must run before any {@code
     * LabelUtil.addNamespace} call, since resource bundles are loaded using {@link
     * Locale#getDefault()} at that moment.
     *
     * @param mode Language mode ({@link #SYSTEM}, {@link #EN}, {@link #ZH} or {@link #JA})
     */
    public static void install(String mode) {
        Locale.setDefault(SYSTEM.equals(mode) ? SYSTEM_LOCALE : Locale.forLanguageTag(mode));
    }

    /**
     * Persists the given language mode. Takes effect on next application start; this does not
     * hot-reload already-loaded resource bundles or already-built UI components.
     *
     * @param mode Language mode ({@link #SYSTEM}, {@link #EN}, {@link #ZH} or {@link #JA})
     */
    public static void switchTo(String mode) {
        try {
            UserPreferences.put(PREF_KEY, mode);
            UserPreferences.save();
        } catch (Exception ex) {
            // Non-fatal: the choice is lost for next launch if it cannot be saved
        }
    }
}
