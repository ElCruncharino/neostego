/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

/**
 * Neutral, library-agnostic descriptor for a plugin-specific command-line option.
 * <p>
 * Plugins declare their extra command-line options using this descriptor so that the command-line
 * layer (and the parsing library it uses) stays entirely outside the plugin SPI.
 */
public class PluginCmdLineOption {
    private final String name;
    private final String altName;
    private final String description;
    private final boolean takesArg;

    /**
     * @param name        Primary option name (e.g. "-b")
     * @param altName     Alternate/long option name (e.g. "--maxBitsUsedPerChannel"), may be null
     * @param description Help description
     * @param takesArg    Whether the option takes an argument
     */
    public PluginCmdLineOption(String name, String altName, String description, boolean takesArg) {
        this.name = name;
        this.altName = altName;
        this.description = description;
        this.takesArg = takesArg;
    }

    /**
     * @return Primary option name
     */
    public String getName() {
        return this.name;
    }

    /**
     * @return Alternate/long option name (may be null)
     */
    public String getAltName() {
        return this.altName;
    }

    /**
     * @return Help description
     */
    public String getDescription() {
        return this.description;
    }

    /**
     * @return Whether the option takes an argument
     */
    public boolean isTakesArg() {
        return this.takesArg;
    }
}
