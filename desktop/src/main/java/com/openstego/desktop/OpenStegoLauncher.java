/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

import com.openstego.desktop.ui.OpenStegoUI;
import com.openstego.desktop.ui.UITheme;
import com.openstego.desktop.util.PluginManager;
import com.openstego.desktop.util.UserPreferences;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Desktop entry point for OpenStego. Launches the Swing GUI when no arguments are given, otherwise
 * delegates to the command-line interface. The platform-independent functionality lives in
 * {@link OpenStego} and the core modules.
 */
public class OpenStegoLauncher {
    /**
     * Logger instance
     */
    private static final Logger logger = Logger.getLogger(OpenStegoLauncher.class.getName());

    private OpenStegoLauncher() {
    }

    /**
     * Main method for launching OpenStego from the command line / desktop.
     *
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        try {
            // Ensure core label namespaces and error codes are registered before anything else
            OpenStego.init();
            // Load the stego plugins
            PluginManager.loadPlugins();
            // Initialize preferences
            UserPreferences.init();

            if (args.length == 0) { // Start GUI
                // Apply the modern FlatLaf look-and-feel using the saved theme preference
                UITheme.install(UITheme.current());
                // Determine default DH and WM plugins
                OpenStegoPlugin<?> dhPlugin = PluginManager.getPluginByName("RandomLSB");
                OpenStegoPlugin<?> wmPlugin = PluginManager.getPluginByName("DWTDugad");
                new OpenStegoUI(dhPlugin, wmPlugin).setVisible(true);
            } else {
                OpenStegoCmd.execute(args);
            }
        } catch (OpenStegoException osEx) {
            if (osEx.getErrorCode() == OpenStegoException.UNHANDLED_EXCEPTION) {
                logger.log(Level.SEVERE, osEx.getMessage(), osEx);
            } else {
                System.err.println(osEx.getMessage());
            }
        } catch (Exception ex) {
            logger.log(Level.SEVERE, ex.getMessage(), ex);
        }
    }
}
