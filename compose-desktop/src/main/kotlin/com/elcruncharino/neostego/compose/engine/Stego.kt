/*
 * Thin bridge from the Compose UI to the toolkit-agnostic :core engine, plus an AWT file picker
 * that is safe to call whether or not we're already on the EDT. Mirrors the Swing embed flow
 * (OpenStegoUI.embedData): reset config, set compression/encryption, embed, write the stego file.
 */
package com.elcruncharino.neostego.compose.engine

import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoConfig
import com.openstego.desktop.plugin.adaptive.AdaptiveConfig
import com.openstego.desktop.plugin.jpeguniward.JpegUniwardConfig
import com.openstego.desktop.plugin.lsb.LSBConfig
import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate
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
private fun sanitizeScale(s: Double): Float? = if (s in 0.5..8.0) (Math.round(s * 100.0) / 100.0).toFloat() else null

/**
 * A data-hiding algorithm with its :core description and the file extensions it accepts as a cover
 * (readable) and can write as a stego file (writable) — used to filter the cover/output pickers.
 */
data class AlgoInfo(
    val name: String,
    val description: String,
    val coverExtensions: List<String>,
    val stegoExtensions: List<String>,
    val optionsKind: OptionsKind,
)

/** Which advanced-options panel an algorithm offers (matches the Swing EmbedOptionsUIFactory). */
enum class OptionsKind { NONE, LSB, ADAPTIVE, JPEG }

private fun optionsKindFor(name: String): OptionsKind = when (name) {
    "LSB", "RandomLSB", "RandomLSBMatch" -> OptionsKind.LSB
    "Adaptive" -> OptionsKind.ADAPTIVE
    "JpegUniward" -> OptionsKind.JPEG
    else -> OptionsKind.NONE
}

/** Per-algorithm advanced embed options; only the fields for the selected algorithm are applied. */
data class AdvancedOptions(
    val maxBitsPerChannel: Int = 3, // LSB family
    val cmd: Boolean = true, // Adaptive
    val cmdMu: Double = 3.0, // Adaptive
    val quality: Int = 90, // JpegUniward
)

/** The data-hiding algorithms (RandomLSB, Adaptive, JpegUniward, F5, WavLSB, ...) with metadata. */
fun dataHidingAlgorithms(): List<AlgoInfo> = PluginManager.getDataHidingPlugins().map { p ->
    // Initialise the plugin config first: some plugins (e.g. JpegUniward, whose cover formats
    // depend on SI vs plain mode) read this.config inside get*FileExtensions and NPE otherwise.
    runCatching { p.resetConfig() }
    AlgoInfo(
        name = p.name,
        description = p.description,
        coverExtensions = runCatching { p.readableFileExtensions }.getOrDefault(emptyList()),
        // BMP last: prefer PNG-style output in the picker/hint (see preferNonBmp).
        stegoExtensions = preferNonBmp(runCatching { p.writableFileExtensions }.getOrDefault(emptyList())),
        optionsKind = optionsKindFor(p.name),
    )
}

private fun commandExists(cmd: String): Boolean = try {
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

/**
 * Open a multi-select file chooser (native KDE/GNOME when available, else Swing), optionally
 * restricted to [extensions]. Returns the chosen paths, or an empty list if cancelled. Used to pick
 * the multiple covers for batch and split embedding.
 */
fun pickFiles(extensions: List<String> = emptyList(), filterLabel: String = "Files"): List<String> {
    nativeDialogTool?.let { tool ->
        val home = System.getProperty("user.home")
        val glob = extensions.joinToString(" ") { "*.$it" }
        val cmd = when (tool) {
            "kdialog" -> buildList {
                add("kdialog")
                add("--multiple")
                add("--separate-output") // one path per line
                add("--getopenfilename")
                add(home)
                if (extensions.isNotEmpty()) add("$glob|$filterLabel")
            }
            else -> buildList {
                add("zenity")
                add("--file-selection")
                add("--multiple")
                add("--separator=\n") // newline-separate so paths with spaces survive
                if (extensions.isNotEmpty()) add("--file-filter=$filterLabel | $glob")
            }
        }
        return try {
            val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() == 0) out.split('\n').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    var result = emptyList<String>()
    val task = Runnable {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            if (extensions.isNotEmpty()) {
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                    "$filterLabel (${extensions.joinToString(", ") { "*.$it" }})",
                    *extensions.toTypedArray(),
                )
            }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFiles.map { it.absolutePath }
        }
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}

data class EmbedRequest(
    val algorithm: String,
    val messageFile: String,
    val coverFile: String,
    val outputFile: String,
    val encryptionAlgorithm: String?, // null => no encryption
    val password: String,
    val options: AdvancedOptions = AdvancedOptions(),
    val useRandomImage: Boolean = false, // generate a random-noise cover (image plugins only)
)

/** Shared config setup for embed/batch/split: compression, password (always), encryption, options. */
private fun prepareEmbedConfig(config: OpenStegoConfig, algorithm: String, encryptionAlgorithm: String?, password: String, options: AdvancedOptions) {
    config.setUseCompression(true)
    // Always apply the password: it seeds the bit-placement PRNG (RandomLSB etc.) even without AES.
    config.setPassword(password)
    val encrypt = encryptionAlgorithm != null
    config.setUseEncryption(encrypt)
    if (encrypt) {
        require(password.isNotEmpty()) { "$encryptionAlgorithm encryption needs a password." }
        config.setEncryptionAlgorithm(encryptionAlgorithm)
    }
    applyAdvancedOptions(algorithm, config, options)
}

/** Order extensions so BMP is last: it's lossless but bulky (and AWT can't always encode it), so we
 *  default away from it whenever a better format (e.g. PNG) is available. */
private fun preferNonBmp(exts: List<String>): List<String> = exts.sortedBy { if (it.equals("bmp", ignoreCase = true)) 1 else 0 }

/**
 * The output extension to use for an auto-named stego file: keep the cover's own format when the
 * algorithm can write it (png→png, jpg→jpg), otherwise the first non-BMP writable format.
 */
private fun stegoExtensionFor(algorithm: String, coverFile: String? = null): String {
    val writable = PluginManager.getPluginByName(algorithm)
        ?.let { runCatching { it.writableFileExtensions }.getOrDefault(emptyList()) }
        ?: emptyList()
    val coverExt = coverFile?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
    if (coverExt != null && writable.any { it.equals(coverExt, ignoreCase = true) }) return coverExt
    return preferNonBmp(writable).firstOrNull() ?: "png"
}

private fun applyAdvancedOptions(algorithm: String, config: Any, options: AdvancedOptions) {
    when (optionsKindFor(algorithm)) {
        OptionsKind.LSB -> (config as LSBConfig).setMaxBitsUsedPerChannel(options.maxBitsPerChannel)
        OptionsKind.ADAPTIVE -> (config as AdaptiveConfig).apply {
            setCmd(options.cmd)
            setCmdMu(options.cmdMu)
        }
        OptionsKind.JPEG -> (config as JpegUniwardConfig).setQuality(options.quality)
        OptionsKind.NONE -> Unit
    }
}

/** Runs the embed against :core and writes the stego file. Returns the output path on success. */
fun embed(req: EmbedRequest, onProgress: (Double) -> Unit = {}): String {
    require(req.messageFile.isNotBlank()) { "Choose a message file to hide." }
    // A random-image cover needs no input file — the image plugin generates noise to hide in.
    require(req.useRandomImage || req.coverFile.isNotBlank()) { "Choose a cover file." }
    require(req.outputFile.isNotBlank()) { "Choose where to save the stego file." }

    val plugin = PluginManager.getPluginByName(req.algorithm)
        ?: error("Unknown algorithm: ${req.algorithm}")
    if (req.useRandomImage) {
        require(plugin is DHImagePluginTemplate<*>) { "${req.algorithm} can't generate a random cover; pick a cover file." }
    }
    plugin.resetConfig()
    val config = plugin.config
    prepareEmbedConfig(config, req.algorithm, req.encryptionAlgorithm, req.password, req.options)
    val stego = OpenStego(plugin, config)
    stego.setProgressListener { onProgress(it) }
    // Passing a null cover makes the image plugin synthesise a random-noise cover sized to the payload.
    val cover: File? = if (req.useRandomImage) null else File(req.coverFile)
    val data = stego.embedData(File(req.messageFile), cover, req.outputFile)
    CommonUtil.writeFile(data, req.outputFile)
    return req.outputFile
}

/**
 * Batch embed: hide the same [messageFile] in each of [coverFiles], writing one stego file per cover
 * into [outputDir]. Returns the written paths. Progress runs 0..1 across the whole batch.
 */
fun embedBatch(
    algorithm: String,
    messageFile: String,
    coverFiles: List<String>,
    outputDir: String,
    encryptionAlgorithm: String?,
    password: String,
    options: AdvancedOptions = AdvancedOptions(),
    onProgress: (Double) -> Unit = {},
): List<String> {
    require(messageFile.isNotBlank()) { "Choose a message file to hide." }
    require(coverFiles.isNotEmpty()) { "Choose at least one cover file." }
    require(outputDir.isNotBlank()) { "Choose an output folder." }
    val used = HashSet<String>()
    return coverFiles.mapIndexed { i, cover ->
        val ext = stegoExtensionFor(algorithm, cover)
        val base = File(cover).nameWithoutExtension
        var name = "${base}_stego.$ext"
        var dedup = 1
        while (!used.add(name.lowercase())) name = "${base}_stego_${dedup++}.$ext"
        val out = File(outputDir, name).path
        embed(
            EmbedRequest(algorithm, messageFile, cover, out, encryptionAlgorithm, password, options),
        ) { f -> onProgress((i + f) / coverFiles.size) }
        out
    }
}

/**
 * Split one [messageFile] across [coverFiles] (≥2 image covers): the payload is compressed/encrypted
 * once, then sliced so each cover carries a part. All parts are needed to reassemble. Returns the
 * written stego paths. Image algorithms only.
 */
fun embedSplitCovers(
    algorithm: String,
    messageFile: String,
    coverFiles: List<String>,
    outputDir: String,
    encryptionAlgorithm: String?,
    password: String,
    options: AdvancedOptions = AdvancedOptions(),
): List<String> {
    require(messageFile.isNotBlank()) { "Choose a message file to hide." }
    require(coverFiles.size >= 2) { "Splitting needs at least 2 cover files." }
    require(outputDir.isNotBlank()) { "Choose an output folder." }
    val plugin = PluginManager.getPluginByName(algorithm) as? DHImagePluginTemplate<*>
        ?: error("Splitting across covers is only available for image algorithms.")
    plugin.resetConfig()
    val config = plugin.config
    prepareEmbedConfig(config, algorithm, encryptionAlgorithm, password, options)

    val payload = CommonUtil.fileToBytes(File(messageFile))
    val msgName = File(messageFile).name
    val covers = coverFiles.map { CommonUtil.fileToBytes(File(it)) }
    val coverNames = coverFiles.map { File(it).name }
    val ext = stegoExtensionFor(algorithm, coverFiles.first())
    val stegoPaths = coverFiles.mapIndexed { i, c ->
        File(outputDir, "${File(c).nameWithoutExtension}_part${i + 1}of${coverFiles.size}.$ext").path
    }
    val stegoNames = stegoPaths.map { File(it).name }
    val stegos = MultiCoverPayloadSplitter.embedSplit(payload, msgName, covers, coverNames, stegoNames, config, plugin)
    stegos.forEachIndexed { i, bytes -> CommonUtil.writeFile(bytes, stegoPaths[i]) }
    return stegoPaths
}

/**
 * Reassemble a split payload from all its [stegoFiles] (≥2) into [outputDir]. Tries each image
 * plugin until the parts parse (the files don't record which algorithm produced them). Returns the
 * written message path.
 */
fun extractSplitFiles(stegoFiles: List<String>, password: String, outputDir: String): String {
    require(stegoFiles.size >= 2) { "Reassembling a split needs all its parts (2 or more files)." }
    require(outputDir.isNotBlank()) { "Choose an output folder." }
    val images = stegoFiles.map { CommonUtil.fileToBytes(File(it)) }
    val names = stegoFiles.map { File(it).name }
    var last: Throwable? = null
    for (plugin in PluginManager.getDataHidingPlugins().filterIsInstance<DHImagePluginTemplate<*>>()) {
        plugin.resetConfig()
        val config = plugin.config
        if (password.isNotEmpty()) config.setPassword(password)
        try {
            val out = MultiCoverPayloadSplitter.extractSplit(images, names, config, plugin)
            val msgName = (out[0] as? String).orEmptyName()
            val bytes = out[1] as ByteArray
            val target = File(outputDir, msgName)
            CommonUtil.writeFile(bytes, target.path)
            return target.path
        } catch (e: Throwable) {
            last = e
        }
    }
    throw last ?: IllegalStateException("Could not reassemble the split (wrong/missing parts or password).")
}

/**
 * Maximum embeddable bytes for [coverFile] with the given image algorithm and options, or null if the
 * algorithm isn't image-based or the cover can't be read. Used for the capacity indicator.
 */
fun coverCapacityBytes(algorithm: String, coverFile: String, options: AdvancedOptions): Long? {
    if (coverFile.isBlank()) return null
    val plugin = PluginManager.getPluginByName(algorithm)
    if (plugin !is DHImagePluginTemplate<*>) return null
    return runCatching {
        plugin.resetConfig()
        applyAdvancedOptions(algorithm, plugin.config, options)
        val (w, h) = imageDimensions(coverFile) ?: return null
        plugin.getMaxDataLength(w, h).toLong()
    }.getOrNull()
}

/** Read just the image header for its dimensions (no full decode). */
private fun imageDimensions(path: String): Pair<Int, Int>? = runCatching {
    javax.imageio.ImageIO.createImageInputStream(File(path)).use { iis ->
        val readers = javax.imageio.ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = iis
            Pair(reader.getWidth(0), reader.getHeight(0))
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

/** File size in bytes, or null if missing. */
fun fileSizeBytes(path: String): Long? = path.takeIf { it.isNotBlank() }?.let { File(it).takeIf { f -> f.isFile }?.length() }

/** Open a folder chooser (native KDE/GNOME when available, else Swing). Null if cancelled. */
fun pickDirectory(): String? {
    val home = System.getProperty("user.home")
    nativeDialogTool?.let { tool ->
        val cmd = when (tool) {
            "kdialog" -> listOf("kdialog", "--getexistingdirectory", home)
            else -> listOf("zenity", "--file-selection", "--directory")
        }
        return try {
            val proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
        } catch (e: Exception) {
            null
        }
    }
    var result: String? = null
    val task = Runnable {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}

/**
 * Extract a hidden message from [stegoFile] into [outputDir]. The stego file does not record which
 * algorithm produced it, so this mirrors the Swing UI: try each data-hiding plugin (preferring those
 * whose output format matches the file extension) until one succeeds. Compression/encryption flags
 * are read from the embedded header; the user only supplies the password. Returns the written path.
 */
fun extract(stegoFile: String, password: String, outputDir: String): String {
    require(stegoFile.isNotBlank()) { "Choose a stego file." }
    require(outputDir.isNotBlank()) { "Choose an output folder." }
    val file = File(stegoFile)
    val data = CommonUtil.fileToBytes(file)
    val name = file.name
    val ext = name.substringAfterLast('.', "").lowercase()
    val ordered = PluginManager.getDataHidingPlugins().sortedByDescending { p ->
        runCatching { p.writableFileExtensions.any { it.equals(ext, ignoreCase = true) } }.getOrDefault(false)
    }
    var last: Throwable? = null
    for (plugin in ordered) {
        plugin.resetConfig()
        val config = plugin.config
        if (password.isNotEmpty()) config.setPassword(password)
        try {
            val out = OpenStego(plugin, config).extractData(data, name)
            val msgName = (out[0] as? String).orEmptyName()
            val bytes = out[1] as ByteArray
            val target = File(outputDir, msgName)
            CommonUtil.writeFile(bytes, target.path)
            return target.path
        } catch (e: Throwable) {
            last = e
        }
    }
    throw last ?: IllegalStateException("No hidden data found (wrong password, or not a NeoStego file).")
}

private fun String?.orEmptyName(): String = if (isNullOrBlank()) "extracted.bin" else this
