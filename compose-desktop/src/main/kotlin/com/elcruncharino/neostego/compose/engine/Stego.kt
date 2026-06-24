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
 * Auto-detect the desktop's display scale (e.g. 1.75 on a HiDPI panel), or null if no signal is
 * found. Applied as Compose's LocalDensity so the UI matches native apps — no hardcoded scale or
 * window size, no platform/userbase assumptions. The factor comes from the X resource DPI
 * (Xft.dpi/96, which KDE/GNOME/XFCE publish for X/XWayland clients), with GDK_SCALE/GDK_DPI_SCALE as
 * a fallback. (LocalDensity is used rather than sun.java2d.uiScale because the Compose runtime
 * initialises AWT before main() can set that system property.)
 */
fun detectUiScale(): Float? {
    runCatching {
        val proc = ProcessBuilder("sh", "-c", "xrdb -query 2>/dev/null | awk '/Xft.dpi:/{print \$2}'").start()
        val dpi = proc.inputStream.bufferedReader().readText().trim().toDoubleOrNull()
        proc.waitFor()
        if (dpi != null && dpi > 0) sanitizeScale(dpi / 96.0)?.let { return it }
    }
    val gdk = System.getenv("GDK_SCALE")?.toDoubleOrNull()
    val gdkDpi = System.getenv("GDK_DPI_SCALE")?.toDoubleOrNull()
    if (gdk != null || gdkDpi != null) sanitizeScale((gdk ?: 1.0) * (gdkDpi ?: 1.0))?.let { return it }
    return null
}

/** Accept plausible scales only, rounded to 2 decimals; reject noise. */
private fun sanitizeScale(s: Double): Float? =
    if (s in 0.5..8.0) (Math.round(s * 100.0) / 100.0).toFloat() else null

/**
 * A data-hiding algorithm with its :core description and the file extensions it accepts as a cover
 * (readable) and can write as a stego file (writable) — used to filter the cover/output pickers.
 */
data class AlgoInfo(
    val name: String,
    val description: String,
    val coverExtensions: List<String>,
    val stegoExtensions: List<String>,
)

/** The data-hiding algorithms (RandomLSB, Adaptive, JpegUniward, F5, WavLSB, ...) with metadata. */
fun dataHidingAlgorithms(): List<AlgoInfo> =
    PluginManager.getDataHidingPlugins().map { p ->
        AlgoInfo(
            name = p.name,
            description = p.description,
            coverExtensions = runCatching { p.readableFileExtensions }.getOrDefault(emptyList()),
            stegoExtensions = runCatching { p.writableFileExtensions }.getOrDefault(emptyList()),
        )
    }

private fun commandExists(cmd: String): Boolean =
    try {
        ProcessBuilder("sh", "-c", "command -v $cmd").start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

// Prefer the desktop's own file dialog so users get their native KDE/GNOME picker (with places,
// recent files, search) rather than Swing's dated chooser. Resolved once.
private val nativeDialogTool: String? by lazy { listOf("kdialog", "zenity").firstOrNull(::commandExists) }

private fun runNativePicker(tool: String, save: Boolean, extensions: List<String>, label: String): String? {
    val home = System.getProperty("user.home")
    val glob = extensions.joinToString(" ") { "*.$it" }
    val cmd = when (tool) {
        "kdialog" -> buildList {
            add("kdialog")
            add(if (save) "--getsavefilename" else "--getopenfilename")
            add(home)
            if (extensions.isNotEmpty()) add("$glob|$label")
        }
        else -> buildList {
            add("zenity")
            add("--file-selection")
            if (save) {
                add("--save")
                add("--confirm-overwrite")
            }
            if (extensions.isNotEmpty()) add("--file-filter=$label | $glob")
        }
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
 * Open a file chooser, optionally restricted to [extensions] (e.g. listOf("png", "bmp")). Uses the
 * native desktop dialog (KDE kdialog / GNOME zenity) when available; otherwise Swing's JFileChooser.
 * Returns the chosen path, or null if cancelled.
 */
fun pickFile(save: Boolean, extensions: List<String> = emptyList(), filterLabel: String = "Files"): String? {
    nativeDialogTool?.let { return runNativePicker(it, save, extensions, filterLabel) }
    var result: String? = null
    val task = Runnable {
        val chooser = JFileChooser()
        if (extensions.isNotEmpty()) {
            chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "$filterLabel (${extensions.joinToString(", ") { "*.$it" }})",
                *extensions.toTypedArray(),
            )
        }
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
