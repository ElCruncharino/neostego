/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util.cmd;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;

/**
 * Utility class to handle console based password input
 */
public class PasswordInput {
    /**
     * Constructor is private so that this class is not instantiated
     */
    private PasswordInput() {}

    /**
     * Acquires a password in a pipe- and script-safe way. Resolution order:
     * <ol>
     *   <li>the {@code NEOSTEGO_PASSWORD} environment variable (non-interactive override), then</li>
     *   <li>an interactive prompt, only if a real terminal is attached.</li>
     * </ol>
     * When neither is available (e.g. stdin/stdout are pipes), this throws instead of prompting,
     * because the interactive fallback would otherwise hang or write masking characters to stdout
     * and corrupt piped output.
     *
     * @param prompt Prompt for the interactive case
     * @return The password
     * @throws OpenStegoException If no password is available and there is no terminal to prompt on
     */
    public static char[] acquirePassword(String prompt) throws OpenStegoException {
        String env = System.getenv("NEOSTEGO_PASSWORD");
        if (env != null) {
            return env.toCharArray();
        }
        if (System.console() == null) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.PASSWORD_REQUIRED);
        }
        char[] password = System.console().readPassword("%s", prompt);
        return (password == null) ? new char[0] : password;
    }
}
