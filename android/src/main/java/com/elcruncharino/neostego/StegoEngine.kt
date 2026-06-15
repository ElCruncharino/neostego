/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.elcruncharino.neostego

import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoPlugin
import com.openstego.desktop.plugin.adaptive.AdaptiveImagePlugin
import com.openstego.desktop.plugin.randlsb.RandomLSBMatchPlugin
import com.openstego.desktop.plugin.randlsb.RandomLSBPlugin
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate

/**
 * Thin Kotlin wrapper over the core [OpenStego] API for embedding and extracting data.
 */
object StegoEngine {

    /** Embedding algorithm the user can choose for hiding. */
    enum class Algorithm { ADAPTIVE, MATCHING }

    /**
     * Embeds [message] into [cover] with the chosen [algorithm], optionally encrypting with
     * [password], returning PNG stego bytes.
     */
    fun embed(
        algorithm: Algorithm,
        embedFileName: Boolean,
        message: ByteArray,
        msgName: String,
        cover: ByteArray,
        coverName: String,
        password: CharArray?
    ): ByteArray {
        val plugin: OpenStegoPlugin<*> = when (algorithm) {
            Algorithm.ADAPTIVE -> AdaptiveImagePlugin()
            Algorithm.MATCHING -> RandomLSBMatchPlugin()
        }
        plugin.resetConfig()
        val config = plugin.config
        config.isUseCompression = true
        // The file name is stored unencrypted, so embedding it is opt-in (defaults to off for privacy)
        config.isEmbedFileName = embedFileName
        // Clone so this attempt's clearPassword() does not wipe the caller's array
        val pw = password?.copyOf()
        if (pw != null && pw.isNotEmpty()) {
            config.isUseEncryption = true
            config.password = pw
        }
        val stego = OpenStego(plugin, config)
        try {
            return stego.embedData(message, msgName, cover, coverName, "stego.png")
        } finally {
            config.clearPassword()
        }
    }

    /**
     * Returns roughly how many message bytes fit in a cover of [width] x [height] pixels with the
     * given [algorithm]. This is the embeddable (post-compression/encryption) size, so an ordinary
     * file is usually larger since it compresses; it serves as a conservative capacity indicator.
     */
    fun capacityBytes(algorithm: Algorithm, width: Int, height: Int): Int {
        val plugin: DHImagePluginTemplate<*> = when (algorithm) {
            Algorithm.ADAPTIVE -> AdaptiveImagePlugin()
            Algorithm.MATCHING -> RandomLSBMatchPlugin()
        }
        plugin.resetConfig()
        return plugin.getMaxDataLength(width, height)
    }

    /** Result of an extraction: the original embedded file name and its bytes. */
    data class Extracted(val fileName: String, val data: ByteArray)

    /**
     * Extracts hidden data from [stegoData]. The algorithm is auto-detected by trying the
     * content-adaptive plugin first, then Random-LSB (which reads both plain and matching
     * embeddings, including data made by older OpenStego versions). A wrong password or non-stego
     * image fails both attempts.
     */
    fun extract(stegoData: ByteArray, stegoName: String, password: CharArray?): Extracted {
        val makePlugins: List<() -> OpenStegoPlugin<*>> =
            listOf({ AdaptiveImagePlugin() }, { RandomLSBPlugin() })
        var lastError: Exception? = null
        for (make in makePlugins) {
            val plugin = make()
            plugin.resetConfig()
            val config = plugin.config
            val pw = password?.copyOf() // clone per attempt; cleared below
            if (pw != null && pw.isNotEmpty()) {
                config.password = pw
            }
            val stego = OpenStego(plugin, config)
            try {
                val out = stego.extractData(stegoData, stegoName)
                val name = out[0] as? String ?: "extracted.dat"
                val data = out[1] as ByteArray
                return Extracted(name, data)
            } catch (e: Exception) {
                lastError = e
            } finally {
                config.clearPassword()
            }
        }
        throw lastError ?: IllegalStateException("Unable to extract data")
    }
}
