/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Top-level destinations shown in the bottom navigation bar. */
private const val DEST_HIDE = 0
private const val DEST_REVEAL = 1
private const val DEST_WATERMARK = 2

private const val ACTION_HIDE = "com.elcruncharino.neostego.ACTION_HIDE"
private const val ACTION_REVEAL = "com.elcruncharino.neostego.ACTION_REVEAL"

/**
 * What the app was launched to do: which destination to open, plus any files handed in via a share
 * or shortcut so the right screen opens preselected.
 */
data class LaunchTarget(
    val destination: Int = DEST_HIDE,
    val coverUri: Uri? = null,
    val payloadUri: Uri? = null,
    val stegoUri: Uri? = null,
    val splitCoverUris: List<Uri> = emptyList(),
    val wavCover: Boolean = false,
)

class MainActivity : ComponentActivity() {
    // Drives the initial screen + preselection; updated when a new share/shortcut arrives.
    private val launchTarget = mutableStateOf(LaunchTarget())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Prevent screenshots, screen recording and Recents thumbnails from capturing secrets/passwords
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        launchTarget.value = parseLaunch(intent)
        setContent {
            NeoStegoTheme {
                StegoApp(launchTarget.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTarget.value = parseLaunch(intent)
    }

    /**
     * Resolves an incoming intent (launcher, app shortcut, or a share from another app via one of the
     * labeled share-target aliases) into a [LaunchTarget].
     */
    private fun parseLaunch(intent: Intent?): LaunchTarget {
        if (intent == null) return LaunchTarget()
        when (intent.action) {
            ACTION_HIDE -> return LaunchTarget(destination = DEST_HIDE)
            ACTION_REVEAL -> return LaunchTarget(destination = DEST_REVEAL)
        }
        // Which labeled share target was used (set via the activity-alias the chooser launched).
        val isReveal = intent.component?.className?.endsWith("ShareReveal") == true
        val type = intent.type ?: ""
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.parcelableExtra(Intent.EXTRA_STREAM)
                when {
                    uri == null -> LaunchTarget()
                    isReveal -> LaunchTarget(destination = DEST_REVEAL, stegoUri = uri)
                    type.startsWith("image/") -> LaunchTarget(destination = DEST_HIDE, coverUri = uri)
                    type.startsWith("audio/") -> LaunchTarget(destination = DEST_HIDE, coverUri = uri, wavCover = true)
                    else -> LaunchTarget(destination = DEST_HIDE, payloadUri = uri) // any other file → payload
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
                when {
                    uris.isEmpty() -> LaunchTarget()
                    isReveal -> LaunchTarget(destination = DEST_REVEAL, stegoUri = uris.first())
                    else -> LaunchTarget(destination = DEST_HIDE, splitCoverUris = uris) // many covers → split
                }
            }
            else -> LaunchTarget()
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name) as? T
    }

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java)
    } else {
        getParcelableArrayListExtra(name)
    }

private fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Unable to read the selected file")

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IllegalStateException("Unable to write to the selected location")
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment ?: "file"
}

/** MIME type inferred from a file name's extension; used to tag shared/saved output. */
private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "wav" -> "audio/x-wav"
    else -> "application/octet-stream"
}

/**
 * Writes [bytes] to a private cache file and returns an ACTION_SEND intent that shares it via the
 * app's FileProvider. The cache dir is wiped first so a previously shared file does not linger. Note
 * a revealed plaintext briefly lives in app-private cache while the share sheet is open.
 */
private fun buildShareIntent(context: Context, name: String, mime: String, bytes: ByteArray): Intent {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, name)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** A produced output (stego/watermarked image, revealed file, or signature) ready to save or share. */
private data class OutputResult(val name: String, val mime: String, val bytes: ByteArray)

/** Reads just the dimensions of an image without decoding its pixels. Returns 0 if unknown. */
private fun imagePixelCount(context: Context, uri: Uri): Long {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    val w = opts.outWidth.toLong()
    val h = opts.outHeight.toLong()
    return if (w > 0 && h > 0) w * h else 0L
}

/** Reads an image's pixel dimensions without decoding its pixels. Returns null if unknown. */
private fun imageDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
}

/** Formats a byte count as a short human-readable string (e.g. "12 KB", "3.4 MB"). */
private fun humanBytes(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes bytes"
}

/**
 * Returns a warning only when an image genuinely cannot fit in the app's heap, otherwise null.
 *
 * The data-hiding algorithm spreads the payload across the whole image via a password-seeded
 * permutation, so the full pixel buffer must be resident. The real peak is the decoded ARGB_8888
 * bitmap (width*height*4) plus the compressed PNG produced on save — roughly 2.5x the raw pixels.
 */
private fun oversizeWarning(context: Context, uri: Uri): String? {
    val pixels = imagePixelCount(context, uri)
    if (pixels <= 0) return null
    val estPeakBytes = pixels * 4L * 5L / 2L
    val heap = Runtime.getRuntime().maxMemory()
    if (estPeakBytes <= heap * 0.8) return null
    val megapixels = pixels / 1_000_000.0
    return "This image is extremely large (about %.0f megapixels) and is bigger than the memory available to the app. ".format(megapixels) +
        "Try a smaller image."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegoApp(target: LaunchTarget) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var dest by remember { mutableStateOf(DEST_HIDE) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var messageUri by remember { mutableStateOf<Uri?>(null) }
    var stegoUri by remember { mutableStateOf<Uri?>(null) }

    var passwordView by remember { mutableStateOf<EditText?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var algorithm by remember { mutableStateOf(StegoEngine.Algorithm.ADAPTIVE) }
    var embedFileName by remember { mutableStateOf(false) }
    var jpegQuality by remember { mutableStateOf(90) }
    var adaptiveCmd by remember { mutableStateOf(true) }
    var adaptiveCmdMu by remember { mutableStateOf(3.0) }
    var lsbBits by remember { mutableStateOf(3) }
    var showAdvanced by remember { mutableStateOf(false) }
    var useCompression by remember { mutableStateOf(true) }
    var useAes256 by remember { mutableStateOf(false) }
    var splitMode by remember { mutableStateOf(false) }
    val splitCovers = remember { mutableStateListOf<Uri>() }
    var busy by remember { mutableStateOf(false) }
    var capacity by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<OutputResult?>(null) }

    // Apply a share/shortcut launch: open the right screen with the handed-in files preselected.
    LaunchedEffect(target) {
        dest = target.destination
        if (target.wavCover) algorithm = StegoEngine.Algorithm.WAV
        target.coverUri?.let { coverUri = it }
        target.payloadUri?.let { messageUri = it }
        target.stegoUri?.let { stegoUri = it }
        if (target.splitCoverUris.isNotEmpty()) {
            splitMode = true
            splitCovers.clear()
            splitCovers.addAll(target.splitCoverUris)
        }
    }
    // A produced result belongs to the screen that made it; drop it when navigating away.
    LaunchedEffect(dest) {
        result?.bytes?.fill(0)
        result = null
    }

    val splitEligible = algorithm == StegoEngine.Algorithm.ADAPTIVE || algorithm == StegoEngine.Algorithm.MATCHING
    if (!splitEligible && splitMode) splitMode = false

    val options = StegoEngine.Options(jpegQuality, adaptiveCmd, adaptiveCmdMu, lsbBits, useCompression, useAes256)

    LaunchedEffect(coverUri, algorithm, lsbBits, jpegQuality, splitMode) {
        val uri = coverUri
        capacity = if (uri == null || !StegoEngine.isImageAlgorithm(algorithm) || splitMode) {
            null
        } else {
            withContext(Dispatchers.IO) {
                imageDimensions(context, uri)?.let { (w, h) ->
                    runCatching { StegoEngine.capacityBytes(algorithm, w, h, options) }.getOrNull()
                }
            }
        }
    }

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun setResult(r: OutputResult?) {
        result?.bytes?.fill(0)
        result = r
    }

    fun shareResult() {
        val r = result ?: return
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { buildShareIntent(context, r.name, r.mime, r.bytes) }
                context.startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbar.showSnackbar("Unable to share: ${e.message ?: e}")
            }
        }
    }

    // Image selection goes through the modern Photo Picker; non-image inputs use the document picker.
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { coverUri = it ?: coverUri }
    val pickStego = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { stegoUri = it ?: stegoUri }
    val openCoverAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { coverUri = it ?: coverUri }
    val openStegoDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { stegoUri = it ?: stegoUri }
    val openMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { messageUri = it ?: messageUri }
    val pickCovers = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            splitCovers.clear()
            splitCovers.addAll(uris)
        }
    }

    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val r = result
        if (uri != null && r != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, r.bytes) }
                    snackbar.showSnackbar("Saved ${r.name}")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        }
    }

    // --- Split output: one stego PNG per cover, written through a sequence of save dialogs. ---
    var splitParts by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var splitPartIndex by remember { mutableStateOf(-1) }
    val saveSplitPart = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val parts = splitParts
        val idx = splitPartIndex
        if (uri != null && idx in parts.indices) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, parts[idx]) }
                    parts[idx].fill(0)
                    splitPartIndex = idx + 1
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        } else {
            parts.forEach { it.fill(0) }
            splitParts = emptyList()
            splitPartIndex = -1
        }
    }
    LaunchedEffect(splitPartIndex, splitParts.size) {
        val parts = splitParts
        val idx = splitPartIndex
        when {
            parts.isNotEmpty() && idx in parts.indices -> saveSplitPart.launch("stego_part${idx + 1}.png")
            parts.isNotEmpty() && idx >= parts.size -> {
                val count = parts.size
                splitParts = emptyList()
                splitPartIndex = -1
                snackbar.showSnackbar("Saved $count stego images")
            }
        }
    }

    fun runHideSplit() {
        val message = messageUri
        if (splitCovers.size < 2) {
            toast("Choose at least two cover images to split across")
            return
        }
        if (message == null) {
            toast("Choose a file to hide first")
            return
        }
        val pw = readPasswordChars(passwordView)
        busy = true
        scope.launch {
            try {
                val covers = splitCovers.toList()
                val parts = withContext(Dispatchers.IO) {
                    StegoEngine.embedSplit(
                        algorithm,
                        embedFileName,
                        readBytes(context, message),
                        displayName(context, message),
                        covers.map { readBytes(context, it) },
                        covers.map { displayName(context, it) },
                        pw,
                        options,
                    )
                }
                splitParts = parts
                splitPartIndex = 0 // triggers the LaunchedEffect to open the first save dialog
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                pw?.fill(' ')
                busy = false
            }
        }
    }

    fun runHide() {
        if (splitMode) {
            runHideSplit()
            return
        }
        val cover = coverUri
        val message = messageUri
        val coverKind = if (algorithm == StegoEngine.Algorithm.WAV) "audio file" else "image"
        if (cover == null) {
            toast("Choose a cover $coverKind first")
            return
        }
        if (message == null) {
            toast("Choose a file to hide first")
            return
        }
        if (StegoEngine.isImageAlgorithm(algorithm)) {
            oversizeWarning(context, cover)?.let {
                toast(it)
                return
            }
        }
        val pw = readPasswordChars(passwordView)
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    StegoEngine.embed(
                        algorithm,
                        embedFileName,
                        readBytes(context, message),
                        displayName(context, message),
                        readBytes(context, cover),
                        displayName(context, cover),
                        pw,
                        options,
                    )
                }
                val name = StegoEngine.outputName(algorithm)
                setResult(OutputResult(name, mimeForName(name), bytes))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                pw?.fill(' ')
                busy = false
            }
        }
    }

    fun runReveal() {
        val stego = stegoUri
        if (stego == null) {
            toast("Choose a stego file first")
            return
        }
        oversizeWarning(context, stego)?.let {
            toast(it)
            return
        }
        val pw = readPasswordChars(passwordView)
        busy = true
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    StegoEngine.extract(readBytes(context, stego), displayName(context, stego), pw)
                }
                val name = extracted.fileName.ifBlank { "revealed.dat" }
                setResult(OutputResult(name, mimeForName(name), extracted.data))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to reveal data")
            } finally {
                pw?.fill(' ')
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("NeoStego") },
                actions = {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                showAbout = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = dest == DEST_HIDE,
                    onClick = { dest = DEST_HIDE },
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    label = { Text("Hide") },
                )
                NavigationBarItem(
                    selected = dest == DEST_REVEAL,
                    onClick = { dest = DEST_REVEAL },
                    icon = { Icon(Icons.Filled.LockOpen, contentDescription = null) },
                    label = { Text("Reveal") },
                )
                NavigationBarItem(
                    selected = dest == DEST_WATERMARK,
                    onClick = { dest = DEST_WATERMARK },
                    icon = { Icon(Icons.Filled.Verified, contentDescription = null) },
                    label = { Text("Watermark") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })

        if (dest == DEST_WATERMARK) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WatermarkSection(snackbar)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (dest == DEST_HIDE) "Hide a file inside a cover" else "Reveal a hidden file from a cover",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dest == DEST_HIDE) {
                val isWav = algorithm == StegoEngine.Algorithm.WAV
                val needsJpegCover = algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD ||
                    algorithm == StegoEngine.Algorithm.F5
                if (splitMode) {
                    FilePickCard(
                        label = "Cover images (split)",
                        chosen = if (splitCovers.isEmpty()) null else "${splitCovers.size} images selected",
                        hint = "Pick two or more images; the file is spread across them",
                        onPick = { pickCovers.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                } else {
                    FilePickCard(
                        label = if (isWav) {
                            "Cover audio (WAV)"
                        } else if (needsJpegCover) {
                            "Cover image (JPEG)"
                        } else {
                            "Cover image"
                        },
                        chosen = coverUri?.let { displayName(context, it) },
                        hint = when {
                            isWav -> "An uncompressed PCM WAV file"
                            needsJpegCover -> "An existing JPEG to hide the data in"
                            else -> "The image the data will be hidden in"
                        },
                        onPick = {
                            if (isWav) {
                                openCoverAudio.launch(arrayOf("audio/x-wav", "audio/wav", "audio/*"))
                            } else {
                                pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                    )
                    capacity?.let {
                        Text(
                            "Can hide up to about ${humanBytes(it)} in this image",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilePickCard(
                    label = "File to hide",
                    chosen = messageUri?.let { displayName(context, it) },
                    hint = "Any file (document, photo, etc.)",
                    onPick = { openMessage.launch(arrayOf("*/*")) },
                )
            } else {
                FilePickCard(
                    label = "Stego file",
                    chosen = stegoUri?.let { displayName(context, it) },
                    hint = "An image or WAV that has data hidden in it",
                    onPick = { openStegoDoc.launch(arrayOf("image/*", "audio/*")) },
                )
            }

            SecurePasswordField(
                show = showPassword,
                onToggleShow = { showPassword = !showPassword },
                onViewCreated = { passwordView = it },
            )

            if (dest == DEST_HIDE) {
                Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
                        Text("Hiding method", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.SI_UNIWARD,
                            title = "SI-UNIWARD (JPEG)",
                            subtitle = "Side-informed JPEG steganography. Saves a JPEG and is the strongest choice at low embedding rates against modern detectors.",
                            onClick = { algorithm = StegoEngine.Algorithm.SI_UNIWARD },
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.PLAIN_UNIWARD,
                            title = "J-UNIWARD (JPEG cover)",
                            subtitle = "Hides directly in an existing JPEG. Faster and works without the original uncompressed image, but less stealthy than SI-UNIWARD.",
                            onClick = { algorithm = StegoEngine.Algorithm.PLAIN_UNIWARD },
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.F5,
                            title = "F5 (JPEG cover)",
                            subtitle = "Fast, classic JPEG steganography using matrix encoding. Good for small payloads in an existing JPEG.",
                            onClick = { algorithm = StegoEngine.Algorithm.F5 },
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.ADAPTIVE,
                            title = "Adaptive (PNG)",
                            subtitle = "HILL + STC: hides changes in textured areas to resist both statistical and AI steganalysis. Lossless PNG, lower capacity.",
                            onClick = { algorithm = StegoEngine.Algorithm.ADAPTIVE },
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.MATCHING,
                            title = "LSB matching (PNG)",
                            subtitle = "Higher capacity and faster; resists classical steganalysis. Lossless PNG.",
                            onClick = { algorithm = StegoEngine.Algorithm.MATCHING },
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.WAV,
                            title = "Audio (WAV)",
                            subtitle = "Hides data in the samples of an uncompressed PCM WAV file. Output is a WAV; pick an audio cover above.",
                            onClick = { algorithm = StegoEngine.Algorithm.WAV },
                        )

                        if (algorithm == StegoEngine.Algorithm.SI_UNIWARD) {
                            Spacer(Modifier.height(12.dp))
                            Text("JPEG quality: $jpegQuality", fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = jpegQuality.toFloat(),
                                onValueChange = { jpegQuality = it.toInt() },
                                valueRange = 50f..100f,
                            )
                            Text(
                                "Higher quality keeps the image crisper but enlarges the file; 90 is a good default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (algorithm == StegoEngine.Algorithm.ADAPTIVE || algorithm == StegoEngine.Algorithm.MATCHING) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "Advanced ▴" else "Advanced ▾")
                            }
                            if (showAdvanced) {
                                if (algorithm == StegoEngine.Algorithm.ADAPTIVE) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Cluster changes (CMD)", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "Synchronizes neighbouring edits for slightly better resistance. Leave on unless reproducing legacy output.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(checked = adaptiveCmd, onCheckedChange = { adaptiveCmd = it })
                                    }
                                    if (adaptiveCmd) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("Clustering strength (mu): ${"%.1f".format(adaptiveCmdMu)}")
                                        Slider(
                                            value = adaptiveCmdMu.toFloat(),
                                            onValueChange = { adaptiveCmdMu = it.toDouble() },
                                            valueRange = 1f..9f,
                                            steps = 7,
                                        )
                                    }
                                } else { // LSB matching
                                    Spacer(Modifier.height(8.dp))
                                    Text("Bits per channel: $lsbBits")
                                    Slider(
                                        value = lsbBits.toFloat(),
                                        onValueChange = { lsbBits = it.toInt() },
                                        valueRange = 1f..8f,
                                        steps = 6,
                                    )
                                    Text(
                                        "More bits store more data but are easier to detect; 3 balances the two.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        ToggleRow(
                            title = "Compress payload",
                            subtitle = "GZIP the data before hiding. Usually shrinks it; turn off for already-compressed files.",
                            checked = useCompression,
                            onCheckedChange = { useCompression = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        ToggleRow(
                            title = "Use AES-256",
                            subtitle = "Stronger key size than the default AES-128. Only applies when a password is set.",
                            checked = useAes256,
                            onCheckedChange = { useAes256 = it },
                        )
                    }
                }
                if (splitEligible) {
                    Card {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            ToggleRow(
                                title = "Split across covers",
                                subtitle = "Spread one file across several images (pick 2+ above). Each image holds one part; " +
                                    "keep all of them to reveal.",
                                checked = splitMode,
                                onCheckedChange = { splitMode = it },
                            )
                        }
                    }
                }
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Store original file name", fontWeight = FontWeight.SemiBold)
                            Text(
                                "The name is saved unencrypted. Leave off to keep it private; " +
                                    "the file is revealed with a generic name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = embedFileName, onCheckedChange = { embedFileName = it })
                    }
                }
                Text(
                    when (algorithm) {
                        StegoEngine.Algorithm.SI_UNIWARD, StegoEngine.Algorithm.PLAIN_UNIWARD, StegoEngine.Algorithm.F5 ->
                            "Share the saved JPEG as-is. Do not open and re-save it — re-compressing the JPEG destroys the hidden data."
                        StegoEngine.Algorithm.WAV ->
                            "Keep the saved WAV as-is to share. Converting it to MP3/AAC or any lossy audio format destroys the hidden data."
                        else ->
                            "Keep the saved PNG as-is to share. Re-saving or sending it as JPEG (or any other lossy format) destroys the hidden data."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { if (dest == DEST_HIDE) runHide() else runReveal() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Working...")
                } else {
                    Text(if (dest == DEST_HIDE) "Hide" else "Reveal")
                }
            }

            result?.let { r ->
                OutputResultCard(
                    name = r.name,
                    onSave = { saveOutput.launch(r.name) },
                    onShare = { shareResult() },
                )
            }
        }
    }
}

/** Shows a produced output with Save and Share actions. */
@Composable
private fun OutputResultCard(name: String, onSave: () -> Unit, onShare: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Ready: $name", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("NeoStego${if (version.isNotEmpty()) " $version" else ""}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hide files inside images or audio, and embed/verify robust watermarks. All processing " +
                        "happens on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Based on OpenStego by Samir Vaidya. Licensed under the GNU General Public License v2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A labelled switch row used for on/off options. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The watermarking workflow: generate a signature, embed it as a robust watermark, or verify a
 * suspect image against a signature. Self-contained (its own state, pickers and password field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkSection(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(0) } // 0 = generate, 1 = embed, 2 = verify
    var algo by remember { mutableStateOf(StegoEngine.WmAlgorithm.DWT_SVD) }
    var busy by remember { mutableStateOf(false) }

    var sigUri by remember { mutableStateOf<Uri?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var markedUri by remember { mutableStateOf<Uri?>(null) }
    var outputJpeg by remember { mutableStateOf(false) }
    var jpegQuality by remember { mutableStateOf(90) }
    var verdict by remember { mutableStateOf<StegoEngine.WmVerdict?>(null) }

    var passwordView by remember { mutableStateOf<EditText?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OutputResult?>(null) }

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }
    fun setResult(r: OutputResult?) {
        result?.bytes?.fill(0)
        result = r
    }

    val openSig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { sigUri = it ?: sigUri }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { coverUri = it ?: coverUri }
    val pickMarked = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { markedUri = it ?: markedUri }

    val saveOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val r = result
        if (uri != null && r != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, r.bytes) }
                    snackbar.showSnackbar("Saved ${r.name}")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                }
            }
        }
    }

    fun shareResult() {
        val r = result ?: return
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { buildShareIntent(context, r.name, r.mime, r.bytes) }
                context.startActivity(Intent.createChooser(intent, "Share"))
            } catch (e: Exception) {
                snackbar.showSnackbar("Unable to share: ${e.message ?: e}")
            }
        }
    }

    LaunchedEffect(mode) {
        verdict = null
        setResult(null)
    }

    fun runGenerate() {
        val pw = readPasswordChars(passwordView)
        if (pw == null) {
            toast("Enter a password to key the signature")
            return
        }
        busy = true
        scope.launch {
            try {
                val sig = withContext(Dispatchers.IO) { StegoEngine.generateSignature(algo, pw) }
                setResult(OutputResult("watermark.sig", "application/octet-stream", sig))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to generate signature")
            } finally {
                pw.fill(' ')
                busy = false
            }
        }
    }

    fun runEmbed() {
        val cover = coverUri
        val sig = sigUri
        if (cover == null) {
            toast("Choose an image to watermark")
            return
        }
        if (sig == null) {
            toast("Choose the signature file")
            return
        }
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    StegoEngine.embedMark(
                        algo,
                        readBytes(context, sig),
                        readBytes(context, cover),
                        displayName(context, cover),
                        outputJpeg,
                        jpegQuality,
                    )
                }
                val name = StegoEngine.markOutputName(outputJpeg)
                setResult(OutputResult(name, mimeForName(name), bytes))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to embed watermark")
            } finally {
                busy = false
            }
        }
    }

    fun runVerify() {
        val marked = markedUri
        val sig = sigUri
        if (marked == null) {
            toast("Choose the image to check")
            return
        }
        if (sig == null) {
            toast("Choose the signature file")
            return
        }
        busy = true
        verdict = null
        scope.launch {
            try {
                verdict = withContext(Dispatchers.IO) {
                    StegoEngine.checkMark(algo, readBytes(context, marked), displayName(context, marked), readBytes(context, sig))
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to verify watermark")
            } finally {
                busy = false
            }
        }
    }

    // A simple segmented selector for the three watermark operations.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        WatermarkModeButton("Generate", mode == 0, Modifier.weight(1f)) { mode = 0 }
        WatermarkModeButton("Embed", mode == 1, Modifier.weight(1f)) { mode = 1 }
        WatermarkModeButton("Verify", mode == 2, Modifier.weight(1f)) { mode = 2 }
    }

    Text(
        when (mode) {
            0 -> "Create a password-keyed signature file to watermark with"
            1 -> "Embed a signature into an image as a robust watermark"
            else -> "Check whether an image carries a watermark"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).selectableGroup()) {
            Text("Algorithm", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            AlgorithmOption(
                selected = algo == StegoEngine.WmAlgorithm.DWT_SVD,
                title = "DWT-SVD (recommended)",
                subtitle = "Modern blind, multi-bit watermark. Survives JPEG re-compression, noise, blur and small crops.",
                onClick = { algo = StegoEngine.WmAlgorithm.DWT_SVD },
            )
            AlgorithmOption(
                selected = algo == StegoEngine.WmAlgorithm.DUGAD,
                title = "DWT-Dugad",
                subtitle = "Classic wavelet spread-spectrum watermark detected by correlation.",
                onClick = { algo = StegoEngine.WmAlgorithm.DUGAD },
            )
            AlgorithmOption(
                selected = algo == StegoEngine.WmAlgorithm.XIE,
                title = "DWT-Xie",
                subtitle = "Wavelet watermark embedded in the approximation sub-band.",
                onClick = { algo = StegoEngine.WmAlgorithm.XIE },
            )
        }
    }

    when (mode) {
        0 -> {
            SecurePasswordField(
                show = showPassword,
                onToggleShow = { showPassword = !showPassword },
                onViewCreated = { passwordView = it },
            )
            Text(
                "The same password always produces the same signature. Save the .sig file - you need it to " +
                    "embed and to verify.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        1 -> {
            FilePickCard(
                label = "Image to watermark",
                chosen = coverUri?.let { displayName(context, it) },
                hint = "The image the watermark is embedded into",
                onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            FilePickCard(
                label = "Signature file",
                chosen = sigUri?.let { displayName(context, it) },
                hint = "The .sig produced by Generate",
                onPick = { openSig.launch(arrayOf("*/*")) },
            )
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    ToggleRow(
                        title = "Output JPEG",
                        subtitle = "Robust watermarks survive JPEG, so a smaller JPEG is fine. Off saves a lossless PNG.",
                        checked = outputJpeg,
                        onCheckedChange = { outputJpeg = it },
                    )
                    if (outputJpeg) {
                        Spacer(Modifier.height(8.dp))
                        Text("JPEG quality: $jpegQuality", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = jpegQuality.toFloat(),
                            onValueChange = { jpegQuality = it.toInt() },
                            valueRange = 50f..100f,
                        )
                    }
                }
            }
        }
        else -> {
            FilePickCard(
                label = "Image to check",
                chosen = markedUri?.let { displayName(context, it) },
                hint = "The image you want to test for a watermark",
                onPick = { pickMarked.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            FilePickCard(
                label = "Signature file",
                chosen = sigUri?.let { displayName(context, it) },
                hint = "The .sig the image should carry",
                onPick = { openSig.launch(arrayOf("*/*")) },
            )
            verdict?.let { WatermarkVerdictCard(it) }
        }
    }

    Button(
        onClick = {
            when (mode) {
                0 -> runGenerate()
                1 -> runEmbed()
                else -> runVerify()
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(12.dp))
            Text("Working...")
        } else {
            Text(
                when (mode) {
                    0 -> "Generate"
                    1 -> "Embed"
                    else -> "Verify"
                },
            )
        }
    }

    if (mode != 2) {
        result?.let { r ->
            OutputResultCard(
                name = r.name,
                onSave = { saveOutput.launch(r.name) },
                onShare = { shareResult() },
            )
        }
    }
}

@Composable
private fun WatermarkModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/** Renders a watermark verification result with a colour-coded verdict. */
@Composable
private fun WatermarkVerdictCard(verdict: StegoEngine.WmVerdict) {
    // Pair every verdict with a distinct icon as well as a colour, so the result is not conveyed
    // by colour alone (colour-blind / low-vision users) and reads correctly under TalkBack.
    val (label, level, icon) = when {
        verdict.present -> Triple("Watermark present", VerdictLevel.PRESENT, Icons.Filled.CheckCircle)
        verdict.weak -> Triple("Weak / uncertain watermark", VerdictLevel.WEAK, Icons.Filled.Warning)
        else -> Triple("No watermark detected", VerdictLevel.ABSENT, Icons.Filled.Cancel)
    }
    // Render the verdict on its own contrast-checked container (see verdictColors) rather than on the
    // dynamic surfaceVariant, so the AA contrast holds regardless of the Material You palette/theme.
    val status = verdictColors(level)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(status.container)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = status.content)
                Text(label, fontWeight = FontWeight.SemiBold, color = status.content)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Correlation %.2f (strong ≥ %.2f, weak ≥ %.2f)".format(verdict.correlation, verdict.high, verdict.low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlgorithmOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // The whole row is the selectable target (≥48dp); it owns the click and exposes the
            // RadioButton role + selected state to TalkBack, so the inner button takes onClick=null.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilePickCard(label: String, chosen: String?, hint: String, onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                // Merge the label + current selection so TalkBack announces the card as one unit
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label. ${chosen ?: hint}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    chosen ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.semantics {
                    contentDescription = (if (chosen == null) "Choose " else "Change ") + label
                },
            ) { Text(if (chosen == null) "Choose" else "Change") }
        }
    }
}

/**
 * Reads the password directly from the EditText as a char[], without ever creating a String, so it
 * can be wiped after use. Returns null when empty.
 */
private fun readPasswordChars(editText: EditText?): CharArray? {
    val editable = editText?.text ?: return null
    val length = editable.length
    if (length == 0) {
        return null
    }
    val chars = CharArray(length)
    editable.getChars(0, length, chars, 0)
    return chars
}

/**
 * A password field backed by a native EditText. Unlike a Compose TextField (whose value is a String
 * that cannot be wiped), this lets the password be read out as a char[] and erased after use.
 */
@Composable
private fun SecurePasswordField(
    show: Boolean,
    onToggleShow: () -> Unit,
    onViewCreated: (EditText) -> Unit,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Password (optional)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleShow) { Text(if (show) "Hide" else "Show") }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Password, optional" },
                factory = { ctx ->
                    EditText(ctx).apply {
                        setSingleLine(true)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        transformationMethod = PasswordTransformationMethod.getInstance()
                        // Label the native field for TalkBack; the visual "Password (optional)"
                        // header above is a separate composable and is not otherwise associated.
                        hint = "Password (optional)"
                        contentDescription = "Password, optional"
                        onViewCreated(this)
                    }
                },
                update = { et ->
                    et.setTextColor(textColor)
                    et.setHintTextColor(hintColor)
                    et.highlightColor = accentColor
                    et.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
                    et.setSelection(et.text.length)
                },
            )
        }
    }
}
