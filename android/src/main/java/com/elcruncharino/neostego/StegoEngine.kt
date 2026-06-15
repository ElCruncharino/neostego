/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.elcruncharino.neostego

import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoPlugin
import com.openstego.desktop.plugin.adaptive.AdaptiveConfig
import com.openstego.desktop.plugin.adaptive.AdaptiveImagePlugin
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardPlugin
import com.openstego.desktop.plugin.lsb.LSBConfig
import com.openstego.desktop.plugin.randlsb.RandomLSBMatchPlugin
import com.openstego.desktop.plugin.randlsb.RandomLSBPlugin
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate

/**
 * Thin Kotlin wrapper over the core [OpenStego] API for embedding and extracting data.
 */
object StegoEngine {

    /** Embedding algorithm the user can choose for hiding. */
    enum class Algorithm { ADAPTIVE, MATCHING, SI_UNIWARD }

    /**
     * Per-algorithm tuning the user can adjust. Defaults match the core plugin defaults, so an
     * untouched UI behaves exactly as before. Only the field relevant to the chosen algorithm is read.
     */
    data class Options(
        val jpegQuality: Int = 90,
        val adaptiveCmd: Boolean = true,
        val adaptiveCmdMu: Double = 3.0,
        val lsbBitsPerChannel: Int = 3
    )

    /** True if [data] begins with the JPEG SOI marker (so it must be handled by the JPEG plugin). */
    private fun isJpeg(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()

    /** Default output file name (and thus extension) the plugin should produce for [algorithm]. */
    fun outputName(algorithm: Algorithm): String =
        if (algorithm == Algorithm.SI_UNIWARD) "stego.jpg" else "stego.png"

    private fun newPlugin(algorithm: Algorithm): OpenStegoPlugin<*> = when (algorithm) {
        Algorithm.ADAPTIVE -> AdaptiveImagePlugin()
        Algorithm.MATCHING -> RandomLSBMatchPlugin()
        Algorithm.SI_UNIWARD -> JpegUniwardPlugin()
    }

    /** Applies the algorithm-specific [options] onto whichever typed config [plugin] carries. */
    private fun applyOptions(config: Any?, options: Options) {
        when (config) {
            is JpegUniwardConfig -> config.quality = options.jpegQuality
            is AdaptiveConfig -> {
                config.isCmd = options.adaptiveCmd
                config.cmdMu = options.adaptiveCmdMu
            }
            is LSBConfig -> config.maxBitsUsedPerChannel = options.lsbBitsPerChannel
        }
    }

    /**
     * Embeds [message] into [cover] with the chosen [algorithm] and [options], optionally encrypting
     * with [password]. Returns stego bytes in the algorithm's native format: PNG for ADAPTIVE/MATCHING,
     * baseline JPEG for SI_UNIWARD. The bytes are returned verbatim and must be saved as-is.
     */
    fun embed(
        algorithm: Algorithm,
        embedFileName: Boolean,
        message: ByteArray,
        msgName: String,
        cover: ByteArray,
        coverName: String,
        password: CharArray?,
        options: Options = Options()
    ): ByteArray {
        val plugin = newPlugin(algorithm)
        plugin.resetConfig()
        val config = plugin.config
        config.isUseCompression = true
        // The file name is stored unencrypted, so embedding it is opt-in (defaults to off for privacy)
        config.isEmbedFileName = embedFileName
        applyOptions(config, options)
        // Clone so this attempt's clearPassword() does not wipe the caller's array
        val pw = password?.copyOf()
        if (pw != null && pw.isNotEmpty()) {
            config.isUseEncryption = true
            config.password = pw
        }
        val stego = OpenStego(plugin, config)
        try {
            return stego.embedData(message, msgName, cover, coverName, outputName(algorithm))
        } finally {
            config.clearPassword()
        }
    }

    /**
     * Returns roughly how many message bytes fit in a cover of [width] x [height] pixels with the
     * given [algorithm] and [options]. This is the embeddable (post-compression/encryption) size, so
     * an ordinary file is usually larger since it compresses; it serves as a conservative indicator.
     */
    fun capacityBytes(
        algorithm: Algorithm,
        width: Int,
        height: Int,
        options: Options = Options()
    ): Int {
        val plugin = newPlugin(algorithm) as DHImagePluginTemplate<*>
        plugin.resetConfig()
        applyOptions(plugin.config, options)
        return plugin.getMaxDataLength(width, height)
    }

    /** Result of an extraction: the original embedded file name and its bytes. */
    data class Extracted(val fileName: String, val data: ByteArray)

    /**
     * Extracts hidden data from [stegoData]. The algorithm is auto-detected: JPEG input is handled by
     * the SI-UNIWARD plugin, while PNG/BMP input is tried against the content-adaptive plugin first,
     * then Random-LSB (which reads both plain and matching embeddings, including data made by older
     * OpenStego versions). A wrong password or non-stego image fails every attempt.
     */
    fun extract(stegoData: ByteArray, stegoName: String, password: CharArray?): Extracted {
        val makePlugins: List<() -> OpenStegoPlugin<*>> =
            if (isJpeg(stegoData)) {
                listOf({ JpegUniwardPlugin() })
            } else {
                listOf({ AdaptiveImagePlugin() }, { RandomLSBPlugin() })
            }
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
