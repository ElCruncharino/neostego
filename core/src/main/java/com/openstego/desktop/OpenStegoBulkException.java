/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom exception class to store multiple errors
 */
public class OpenStegoBulkException extends Exception {

    /**
     * Errors added to this bulk exception, keyed by e.g. filename, in the order they were added
     */
    private final List<Map.Entry<String, OpenStegoException>> entries = new ArrayList<>();

    /**
     * Add an exception to this bulk list
     *
     * @param key Key for the exception (e.g. filename)
     * @param e   Exception to be added
     */
    public void add(String key, OpenStegoException e) {
        entries.add(Map.entry(key, e));
    }

    /**
     * Return the current list of errors
     */
    public List<Map.Entry<String, OpenStegoException>> getEntries() {
        return entries;
    }

    /**
     * Throw this exception if list is not empty
     */
    public void throwIfRequired() throws OpenStegoBulkException {
        if (!entries.isEmpty()) {
            throw this;
        }
    }
}
