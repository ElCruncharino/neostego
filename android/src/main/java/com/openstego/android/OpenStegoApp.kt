/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.android

import android.app.Application
import com.openstego.desktop.OpenStego
import com.openstego.desktop.image.ImageCodecRegistry
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Application entry point. Registers the Android image codec for the core steganography engine,
 * installs the full BouncyCastle security provider (so legacy-encrypted files can be decrypted), and
 * initializes core label namespaces.
 */
class OpenStegoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Replace Android's stripped-down "BC" provider with the full BouncyCastle implementation so
        // that the legacy PBEWithHmacSHA256AndAES_* algorithms (used by older stego files) are available.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        // Provide the platform image backend to the core (instead of ServiceLoader discovery)
        ImageCodecRegistry.set(BitmapImageCodec())
        // Ensure core label namespaces / error codes are registered
        OpenStego.init()
    }
}
