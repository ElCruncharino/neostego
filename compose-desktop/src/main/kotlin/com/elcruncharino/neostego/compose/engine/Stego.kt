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

/**
 * Auto-detect the display scale and apply it as the Java2D/Compose UI scale, so the window renders
 * natively at the desktop's scaling instead of an unscaled 1x (tiny on HiDPI) or an inherited value.
 * Must run before any AWT/Compose graphics initialises. No platform/userbase assumptions: it derives
 * the factor from the X resource DPI (which KDE/GNOME/XFCE all publish for X/XWayland clients), with
 * the GDK_SCALE/GDK_DPI_SCALE environment as a fallback. Does nothing if no signal is found.
 */
fun applyAutoDetectedUiScale() {
    val scale = detectDisplayScale() ?: return
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    System.setProperty("sun.java2d.uiScale", scale.toString())
}

private fun detectDisplayScale(): Double? {
    // Xft.dpi is the per-session display DPI; scale = dpi / 96 (the reference DPI).
    runCatching {
        val proc = ProcessBuilder("sh", "-c", "xrdb -query 2>/dev/null | awk '/Xft.dpi:/{print \$2}'").start()
        val dpi = proc.inputStream.bufferedReader().readText().trim().toDoubleOrNull()
        proc.waitFor()
        if (dpi != null && dpi > 0) sanitizeScale(dpi / 96.0)?.let { return it }
    }
    // Fallback: GDK scaling hints (integer GDK_SCALE times fractional GDK_DPI_SCALE).
    val gdk = System.getenv("GDK_SCALE")?.toDoubleOrNull()
    val gdkDpi = System.getenv("GDK_DPI_SCALE")?.toDoubleOrNull()
    if (gdk != null || gdkDpi != null) sanitizeScale((gdk ?: 1.0) * (gdkDpi ?: 1.0))?.let { return it }
    return null
}

/** Accept plausible scales only, rounded to 2 decimals; reject noise. */
private fun sanitizeScale(s: Double): Double? =
    if (s in 0.5..8.0) Math.round(s * 100.0) / 100.0 else null

/** A data-hiding algorithm and its human-readable description (from the :core plugin). */
data class AlgoInfo(val name: String, val description: String)

/** The data-hiding algorithms (RandomLSB, Adaptive, JpegUniward, F5, WavLSB, ...) with descriptions. */
fun dataHidingAlgorithms(): List<AlgoInfo> =
    PluginManager.getDataHidingPlugins().map { AlgoInfo(it.name, it.description) }

private fun commandExists(cmd: String): Boolean =
    try {
        ProcessBuilder("sh", "-c", "command -v $cmd").start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

// Prefer the desktop's own file dialog so users get their native KDE/GNOME picker (with places,
// recent files, search) rather than Swing's dated chooser. Resolved once.
private val nativeDialogTool: String? by lazy { listOf("kdialog", "zenity").firstOrNull(::commandExists) }

private fun runNativePicker(tool: String, save: Boolean): String? {
    val home = System.getProperty("user.home")
    val cmd = when (tool) {
        "kdialog" -> if (save) listOf("kdialog", "--getsavefilename", home) else listOf("kdialog", "--getopenfilename", home)
        else -> if (save) listOf("zenity", "--file-selection", "--save", "--confirm-overwrite") else listOf("zenity", "--file-selection")
    }
    return try {
        val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else null // exit!=0 => user cancelled
    } catch (e: Exception) {
        null
    }
}

/**
 * Open a file chooser. Uses the native desktop dialog (KDE kdialog / GNOME zenity) when available;
 * otherwise falls back to Swing's JFileChooser. Returns the chosen path, or null if cancelled.
 */
fun pickFile(save: Boolean): String? {
    nativeDialogTool?.let { return runNativePicker(it, save) }
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
