/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.util.cmd;

import com.openstego.desktop.OpenStegoException;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Utility class to handle console based password input
 */
public class PasswordInput {
    /**
     * Constructor is private so that this class is not instantiated
     */
    private PasswordInput() {
    }

    /**
     * Method to read password from the console
     *
     * @param prompt Prompt for the password input
     * @return The password as entered by the user
     * @throws OpenStegoException Processing issue
     */
    public static char[] readPassword(String prompt) throws OpenStegoException {
        // Prefer the console, which reads directly into a char[] without echoing
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("%s", prompt);
            return (password == null) ? new char[0] : password;
        }

        // Fallback (e.g. when there is no attached console): mask using the eraser thread
        EraserThread et = new EraserThread(prompt);
        Thread mask = new Thread(et);
        mask.start();

        String password;
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        try {
            password = in.readLine();
        } catch (IOException ioEx) {
            throw new OpenStegoException(ioEx);
        }

        // Stop masking
        et.stopMasking();
        System.out.println();

        return (password == null) ? new char[0] : password.toCharArray();
    }

    /**
     * Thread to keep rewriting the input characters with blank space
     */
    static class EraserThread implements Runnable {
        /**
         * Flag for stop condition
         */
        private boolean stop = true;

        /**
         * Constructor
         *
         * @param prompt Prompt for the password input
         */
        public EraserThread(String prompt) {
            System.out.print(prompt);
        }

        /**
         * Implementation of <code>run</code> method
         */
        @Override
        public void run() {
            while (this.stop) {
                System.out.print("\b ");
            }
        }

        /**
         * Instruct the thread to stop masking
         */
        public void stopMasking() {
            this.stop = false;
        }
    }
}
