/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.elcruncharino.neostego

import android.app.Application
import com.openstego.desktop.OpenStego
import com.openstego.desktop.image.ImageCodecRegistry

/**
 * Application entry point. Registers the Android image codec for the core steganography engine and
 * initializes core label namespaces. Legacy-encrypted files are handled by the core using portable
 * primitives, so no extra security provider is required.
 */
class NeoStegoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Provide the platform image backend to the core (instead of ServiceLoader discovery)
        ImageCodecRegistry.set(BitmapImageCodec())
        // Ensure core label namespaces / error codes are registered
        OpenStego.init()
    }
}
