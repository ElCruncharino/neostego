/*
 * Thin bridge from the Compose UI to the toolkit-agnostic :core engine, plus an AWT file picker
 * that is safe to call whether or not we're already on the EDT. Mirrors the Swing embed flow
 * (OpenStegoUI.embedData): reset config, set compression/encryption, embed, write the stego file.
 */
package com.elcruncharino.neostego.compose.engine

import com.openstego.desktop.OpenStego
import com.openstego.desktop.util.CommonUtil
import com.openstego.desktop.util.PluginManager
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Names of the data-hiding algorithms, e.g. RandomLSB, Adaptive, JpegUniward, F5, WavLSB. */
fun dataHidingAlgorithms(): List<String> = PluginManager.getDataHidingPlugins().map { it.name }

/** Open a native file chooser. Safe from the Compose/AWT event thread or a background thread. */
fun pickFile(save: Boolean): String? {
    var result: String? = null
    val task = Runnable {
        val chooser = JFileChooser()
        val outcome = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (outcome == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}

class EmbedRequest(
    val algorithm: String,
    val messageFile: String,
    val coverFile: String,
    val outputFile: String,
    val encryptionAlgorithm: String?, // null => no encryption
    val password: String,
)

/** Runs the embed against :core and writes the stego file. Returns the output path on success. */
fun embed(req: EmbedRequest): String {
    require(req.messageFile.isNotBlank()) { "Choose a message file to hide." }
    require(req.coverFile.isNotBlank()) { "Choose a cover file." }
    require(req.outputFile.isNotBlank()) { "Choose where to save the stego file." }

    val plugin = PluginManager.getPluginByName(req.algorithm)
        ?: error("Unknown algorithm: ${req.algorithm}")
    plugin.resetConfig()
    val config = plugin.config
    config.setUseCompression(true)
    val encrypt = req.encryptionAlgorithm != null && req.password.isNotEmpty()
    config.setUseEncryption(encrypt)
    if (encrypt) {
        config.setEncryptionAlgorithm(req.encryptionAlgorithm)
        config.setPassword(req.password)
    }
    val stego = OpenStego(plugin, config)
    val data = stego.embedData(File(req.messageFile), File(req.coverFile), req.outputFile)
    CommonUtil.writeFile(data, req.outputFile)
    return req.outputFile
}
