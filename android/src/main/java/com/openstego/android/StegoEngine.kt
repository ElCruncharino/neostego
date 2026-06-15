/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.android

import com.openstego.desktop.OpenStego
import com.openstego.desktop.plugin.randlsb.RandomLSBMatchPlugin
import com.openstego.desktop.plugin.randlsb.RandomLSBPlugin

/**
 * Thin Kotlin wrapper over the core [OpenStego] API for embedding and extracting data.
 */
object StegoEngine {

    /**
     * Embeds [message] into [cover], optionally encrypting with [password], returning PNG stego bytes.
     */
    fun embed(
        useMatching: Boolean,
        embedFileName: Boolean,
        message: ByteArray,
        msgName: String,
        cover: ByteArray,
        coverName: String,
        password: CharArray?
    ): ByteArray {
        val plugin = if (useMatching) RandomLSBMatchPlugin() else RandomLSBPlugin()
        plugin.resetConfig()
        val config = plugin.config
        config.isUseCompression = true
        // The file name is stored unencrypted, so embedding it is opt-in (defaults to off for privacy)
        config.isEmbedFileName = embedFileName
        if (password != null && password.isNotEmpty()) {
            config.isUseEncryption = true
            config.password = password
        }
        val stego = OpenStego(plugin, config)
        try {
            return stego.embedData(message, msgName, cover, coverName, "stego.png")
        } finally {
            config.clearPassword()
        }
    }

    /** Result of an extraction: the original embedded file name and its bytes. */
    data class Extracted(val fileName: String, val data: ByteArray)

    /**
     * Extracts hidden data from [stegoData]. Random-LSB extraction handles both plain and matching
     * embeddings (the recovered bits are identical), so the plain plugin is used.
     */
    fun extract(stegoData: ByteArray, stegoName: String, password: CharArray?): Extracted {
        val plugin = RandomLSBPlugin()
        plugin.resetConfig()
        val config = plugin.config
        if (password != null && password.isNotEmpty()) {
            config.password = password
        }
        val stego = OpenStego(plugin, config)
        try {
            val out = stego.extractData(stegoData, stegoName)
            val name = out[0] as? String ?: "extracted.dat"
            val data = out[1] as ByteArray
            return Extracted(name, data)
        } finally {
            config.clearPassword()
        }
    }
}
