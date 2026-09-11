/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

/**
 * Neutral, library-agnostic descriptor for a plugin-specific command-line option.
 * <p>
 * Plugins declare their extra command-line options using this descriptor so that the command-line
 * layer (and the parsing library it uses) stays entirely outside the plugin SPI.
 *
 * @param name        Primary option name (e.g. "-b")
 * @param altName     Alternate/long option name (e.g. "--maxBitsUsedPerChannel"), may be null
 * @param description Help description
 * @param takesArg    Whether the option takes an argument
 */
public record PluginCmdLineOption(String name, String altName, String description, boolean takesArg) {}
