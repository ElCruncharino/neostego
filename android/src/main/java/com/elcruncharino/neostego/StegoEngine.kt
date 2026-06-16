/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego

import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoConfig
import com.openstego.desktop.OpenStegoCrypto
import com.openstego.desktop.OpenStegoPlugin
import com.openstego.desktop.WatermarkingPlugin
import com.openstego.desktop.image.ImageCodecRegistry
import com.openstego.desktop.plugin.adaptive.AdaptiveConfig
import com.openstego.desktop.plugin.adaptive.AdaptiveImagePlugin
import com.openstego.desktop.plugin.dwtdugad.DWTDugadPlugin
import com.openstego.desktop.plugin.dwtsvd.DWTSVDPlugin
import com.openstego.desktop.plugin.dwtxie.DWTXiePlugin
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardPlugin
import com.openstego.desktop.plugin.lsb.LSBConfig
import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter
import com.openstego.desktop.plugin.randlsb.RandomLSBMatchPlugin
import com.openstego.desktop.plugin.randlsb.RandomLSBPlugin
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate
import com.openstego.desktop.plugin.wavlsb.WavLSBPlugin

/**
 * Thin Kotlin wrapper over the core [OpenStego] API for embedding and extracting data.
 */
object StegoEngine {

    /** Embedding algorithm the user can choose for hiding. */
    enum class Algorithm { ADAPTIVE, MATCHING, SI_UNIWARD, WAV }

    /** Robust watermarking algorithm the user can choose. (DWT-Kim is omitted: its detector is an
     *  upstream stub that never verifies, so it is not exposed.) */
    enum class WmAlgorithm { DWT_SVD, DUGAD, XIE }

    /**
     * Per-algorithm tuning the user can adjust. Defaults match the core plugin defaults, so an
     * untouched UI behaves exactly as before. Only the field relevant to the chosen algorithm is read.
     */
    data class Options(
        val jpegQuality: Int = 90,
        val adaptiveCmd: Boolean = true,
        val adaptiveCmdMu: Double = 3.0,
        val lsbBitsPerChannel: Int = 3,
        /** Whether to GZIP-compress the payload before embedding (desktop parity: user-toggleable). */
        val useCompression: Boolean = true,
        /** Use AES-256 instead of the default AES-128 when a password is supplied. */
        val encryptionAes256: Boolean = false
    )

    /** True if [data] begins with the JPEG SOI marker (so it must be handled by the JPEG plugin). */
    private fun isJpeg(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()

    /** True if [data] is a RIFF/WAVE container (so it must be handled by the WAV plugin). */
    private fun isWav(data: ByteArray): Boolean =
        data.size >= 12 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'A'.code.toByte() &&
            data[10] == 'V'.code.toByte() && data[11] == 'E'.code.toByte()

    /** Default output file name (and thus extension) the plugin should produce for [algorithm]. */
    fun outputName(algorithm: Algorithm): String = when (algorithm) {
        Algorithm.SI_UNIWARD -> "stego.jpg"
        Algorithm.WAV -> "stego.wav"
        else -> "stego.png"
    }

    /** True if [algorithm] embeds into images (so capacity/cover pickers are image-based). */
    fun isImageAlgorithm(algorithm: Algorithm): Boolean = algorithm != Algorithm.WAV

    private fun newPlugin(algorithm: Algorithm): OpenStegoPlugin<*> = when (algorithm) {
        Algorithm.ADAPTIVE -> AdaptiveImagePlugin()
        Algorithm.MATCHING -> RandomLSBMatchPlugin()
        Algorithm.SI_UNIWARD -> JpegUniwardPlugin()
        Algorithm.WAV -> WavLSBPlugin()
    }

    private fun newWatermarkPlugin(algorithm: WmAlgorithm): WatermarkingPlugin<*> = when (algorithm) {
        WmAlgorithm.DWT_SVD -> DWTSVDPlugin()
        WmAlgorithm.DUGAD -> DWTDugadPlugin()
        WmAlgorithm.XIE -> DWTXiePlugin()
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

    /** Sets compression and (when a password is present) the AES key size on [config] from [options]. */
    private fun applyCryptoOptions(config: OpenStegoConfig, options: Options, hasPassword: Boolean) {
        config.isUseCompression = options.useCompression
        if (hasPassword && options.encryptionAes256) {
            config.encryptionAlgorithm = OpenStegoCrypto.ALGO_AES256
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
        // The file name is stored unencrypted, so embedding it is opt-in (defaults to off for privacy)
        config.isEmbedFileName = embedFileName
        applyOptions(config, options)
        // Clone so this attempt's clearPassword() does not wipe the caller's array
        val pw = password?.copyOf()
        val hasPassword = pw != null && pw.isNotEmpty()
        if (hasPassword) {
            config.isUseEncryption = true
            config.password = pw
        }
        applyCryptoOptions(config, options, hasPassword)
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
            when {
                isJpeg(stegoData) -> listOf({ JpegUniwardPlugin() })
                isWav(stegoData) -> listOf({ WavLSBPlugin() })
                else -> listOf({ AdaptiveImagePlugin() }, { RandomLSBPlugin() })
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

    // ------------------------------------------------------------------
    // Watermarking (signature generation / embed / verify)
    // ------------------------------------------------------------------

    /**
     * Generates a watermark signature keyed by [password] for the chosen [algorithm]. The signature is a
     * small self-describing blob that must be kept to later embed and verify the watermark; a password is
     * mandatory. The returned bytes are saved as a `.sig` file.
     */
    fun generateSignature(algorithm: WmAlgorithm, password: CharArray): ByteArray {
        val plugin = newWatermarkPlugin(algorithm)
        plugin.resetConfig()
        val config = plugin.config
        val pw = password.copyOf()
        config.password = pw
        val stego = OpenStego(plugin, config)
        try {
            return stego.generateSignature()
        } finally {
            config.clearPassword()
        }
    }

    /**
     * Embeds [signature] into [cover] as a robust watermark. Output is lossless PNG unless [outputJpeg]
     * is set, in which case a JPEG at [jpegQuality] is produced (robust watermarks survive JPEG, so this
     * is a valid choice; ordinary data hiding never uses it). Watermark embedding does not compress or
     * encrypt - the signature is embedded as-is.
     */
    fun embedMark(
        algorithm: WmAlgorithm,
        signature: ByteArray,
        cover: ByteArray,
        coverName: String,
        outputJpeg: Boolean = false,
        jpegQuality: Int = 90
    ): ByteArray {
        val plugin = newWatermarkPlugin(algorithm)
        plugin.resetConfig()
        val stego = OpenStego(plugin, plugin.config)
        val outName = if (outputJpeg) "marked.jpg" else "marked.png"
        val codec = ImageCodecRegistry.get() as? BitmapImageCodec
        codec?.jpegQuality = jpegQuality
        try {
            return stego.embedMark(signature, "signature.sig", cover, coverName, outName)
        } finally {
            codec?.resetJpegQuality()
        }
    }

    /** A watermark verification result: the [correlation] in [0,1] and the plugin's decision thresholds. */
    data class WmVerdict(val correlation: Double, val high: Double, val low: Double) {
        /** True when the watermark is clearly present. */
        val present: Boolean get() = correlation >= high
        /** True when a weak/uncertain watermark is detected. */
        val weak: Boolean get() = correlation in low..high
    }

    /**
     * Checks [marked] for the watermark described by [signature] using [algorithm], returning the
     * correlation together with the plugin's high/low decision thresholds.
     */
    fun checkMark(algorithm: WmAlgorithm, marked: ByteArray, markedName: String, signature: ByteArray): WmVerdict {
        val plugin = newWatermarkPlugin(algorithm)
        plugin.resetConfig()
        val stego = OpenStego(plugin, plugin.config)
        val corr = stego.checkMark(marked, markedName, signature)
        return WmVerdict(corr, plugin.highWatermarkLevel, plugin.lowWatermarkLevel)
    }

    /** Output (and thus format) the watermark embed should produce for the chosen options. */
    fun markOutputName(outputJpeg: Boolean): String = if (outputJpeg) "marked.jpg" else "marked.png"

    // ------------------------------------------------------------------
    // Multi-cover payload splitting
    // ------------------------------------------------------------------

    /**
     * Splits [message] across several image [covers] (one stego image per cover), so a payload larger
     * than any single cover can still be hidden. Compression/encryption are applied once to the whole
     * payload before splitting. Returns one stego PNG per cover, in the same order as [covers]. Only the
     * image LSB-family algorithms (ADAPTIVE, MATCHING) are supported.
     */
    fun embedSplit(
        algorithm: Algorithm,
        embedFileName: Boolean,
        message: ByteArray,
        msgName: String,
        covers: List<ByteArray>,
        coverNames: List<String>,
        password: CharArray?,
        options: Options = Options()
    ): List<ByteArray> {
        val plugin = newPlugin(algorithm) as DHImagePluginTemplate<*>
        plugin.resetConfig()
        val config = plugin.config
        config.isEmbedFileName = embedFileName
        applyOptions(config, options)
        val pw = password?.copyOf()
        val hasPassword = pw != null && pw.isNotEmpty()
        if (hasPassword) {
            config.isUseEncryption = true
            config.password = pw
        }
        applyCryptoOptions(config, options, hasPassword)
        // Internal stego names only drive the output codec format; each algorithm here is lossless PNG.
        val stegoNames = coverNames.indices.map { "stego_part${it + 1}.png" }
        try {
            return MultiCoverPayloadSplitter.embedSplit(message, msgName, covers, coverNames, stegoNames, config, plugin)
        } finally {
            config.clearPassword()
        }
    }

    /**
     * Reassembles a payload split across [stegoImages] (in any order) back into the original file,
     * decrypting with [password] if needed. Returns the original file name and bytes.
     */
    fun extractSplit(
        algorithm: Algorithm,
        stegoImages: List<ByteArray>,
        stegoNames: List<String>,
        password: CharArray?
    ): Extracted {
        val plugin = newPlugin(algorithm) as DHImagePluginTemplate<*>
        plugin.resetConfig()
        val config = plugin.config
        val pw = password?.copyOf()
        if (pw != null && pw.isNotEmpty()) {
            config.password = pw
        }
        try {
            val out = MultiCoverPayloadSplitter.extractSplit(stegoImages, stegoNames, config, plugin)
            val name = out[0] as? String ?: "extracted.dat"
            val data = out[1] as ByteArray
            return Extracted(name, data)
        } finally {
            config.clearPassword()
        }
    }
}
