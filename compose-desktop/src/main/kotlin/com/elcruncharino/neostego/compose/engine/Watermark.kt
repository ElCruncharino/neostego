/*
 * Digital-watermarking bridge to :core (DWTSVD / DWTDugad plugins): generate a signature from a key,
 * embed it into a cover, and verify a watermarked file against a signature. Mirrors the Swing
 * OpenStegoUI watermarking flows.
 */
package com.elcruncharino.neostego.compose.engine

import com.openstego.desktop.OpenStego
import com.openstego.desktop.util.CommonUtil
import com.openstego.desktop.util.PluginManager
import java.io.File

/** The watermarking algorithms (DWTSVD robust, DWTDugad) with descriptions and cover/output formats. */
fun watermarkingAlgorithms(): List<AlgoInfo> = PluginManager.getWatermarkingPlugins().map { p ->
    runCatching { p.resetConfig() }
    AlgoInfo(
        name = p.name,
        description = p.description,
        coverExtensions = runCatching { p.readableFileExtensions }.getOrDefault(emptyList()),
        stegoExtensions = runCatching { p.writableFileExtensions }.getOrDefault(emptyList()),
        optionsKind = OptionsKind.NONE,
    )
}

/** Generate a signature from [key] and write it to [outputSigFile]. Returns the written path. */
fun generateSignature(algorithm: String, key: String, outputSigFile: String): String {
    require(key.isNotBlank()) { "Enter a key to derive the signature from." }
    require(outputSigFile.isNotBlank()) { "Choose where to save the signature (.sig)." }
    val plugin = PluginManager.getPluginByName(algorithm) ?: error("Unknown algorithm: $algorithm")
    plugin.resetConfig()
    val config = plugin.config
    config.setPassword(key)
    val sig = OpenStego(plugin, config).generateSignature()
    CommonUtil.writeFile(sig, outputSigFile)
    return outputSigFile
}

/** Embed [sigFile] into [coverFile], writing the watermarked file to [outputFile]. */
fun embedWatermark(algorithm: String, sigFile: String, coverFile: String, outputFile: String): String {
    require(sigFile.isNotBlank()) { "Choose a signature file (.sig)." }
    require(coverFile.isNotBlank()) { "Choose a cover file." }
    require(outputFile.isNotBlank()) { "Choose where to save the watermarked file." }
    val plugin = PluginManager.getPluginByName(algorithm) ?: error("Unknown algorithm: $algorithm")
    plugin.resetConfig()
    val data = OpenStego(plugin, plugin.config).embedMark(File(sigFile), File(coverFile), outputFile)
    CommonUtil.writeFile(data, outputFile)
    return outputFile
}

enum class VerdictLevel { PRESENT, WEAK, ABSENT }

class Verdict(val correlation: Double, val level: VerdictLevel)

/** Check [watermarkedFile] against [sigFile]; the correlation is bucketed by the plugin's thresholds. */
fun verifyWatermark(algorithm: String, watermarkedFile: String, sigFile: String): Verdict {
    require(watermarkedFile.isNotBlank()) { "Choose the watermarked file to check." }
    require(sigFile.isNotBlank()) { "Choose the original signature file (.sig)." }
    val plugin = PluginManager.getPluginByName(algorithm) ?: error("Unknown algorithm: $algorithm")
    plugin.resetConfig()
    val openStego = OpenStego(plugin, plugin.config)
    val correlation = openStego.checkMark(File(watermarkedFile), File(sigFile))
    val level = when {
        correlation > plugin.highWatermarkLevel -> VerdictLevel.PRESENT
        correlation > plugin.lowWatermarkLevel -> VerdictLevel.WEAK
        else -> VerdictLevel.ABSENT
    }
    return Verdict(correlation, level)
}
