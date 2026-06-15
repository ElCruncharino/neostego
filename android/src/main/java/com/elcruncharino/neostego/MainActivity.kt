/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Image handed to us via the system share sheet (ACTION_SEND); drives the initial selection.
    private val sharedImage = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots, screen recording and Recents thumbnails from capturing secrets/passwords
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        sharedImage.value = sharedImageFromIntent(intent)
        setContent {
            NeoStegoTheme {
                StegoApp(initialImage = sharedImage.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImageFromIntent(intent)?.let { sharedImage.value = it }
    }

    /** Extracts a single image [Uri] from an incoming SEND / SEND_MULTIPLE intent, or null. */
    private fun sharedImageFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> null
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java)
    else getParcelableExtra(name) as? T

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(name, T::class.java)
    else getParcelableArrayListExtra(name)

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
 * permutation, so the full pixel buffer must be resident (it can't be streamed row-by-row). The
 * real peak is the decoded ARGB_8888 bitmap (width*height*4) plus the compressed PNG produced on
 * save — roughly 2.5x the raw pixels. We only refuse when even that won't fit, so a hard
 * OutOfMemoryError becomes a clear, recoverable message instead. With largeHeap enabled this never
 * triggers for ordinary phone photos.
 */
private fun oversizeWarning(context: Context, uri: Uri): String? {
    val pixels = imagePixelCount(context, uri)
    if (pixels <= 0) return null // unknown dimensions; let the normal path attempt it
    val estPeakBytes = pixels * 4L * 5L / 2L // decoded bitmap + compressed output + working overhead
    val heap = Runtime.getRuntime().maxMemory()
    if (estPeakBytes <= heap * 0.8) return null
    val megapixels = pixels / 1_000_000.0
    return "This image is extremely large (about %.0f megapixels) and is bigger than the memory available to the app. ".format(megapixels) +
        "Try a smaller image."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegoApp(initialImage: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(0) } // 0 = Hide, 1 = Reveal
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var messageUri by remember { mutableStateOf<Uri?>(null) }
    var stegoUri by remember { mutableStateOf<Uri?>(null) }

    // An image shared into the app preselects both roles, so it is ready whether the user hides into
    // it (Hide tab, the default) or reveals from it (Reveal tab).
    LaunchedEffect(initialImage) {
        initialImage?.let {
            coverUri = it
            stegoUri = it
        }
    }
    var passwordView by remember { mutableStateOf<EditText?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var algorithm by remember { mutableStateOf(StegoEngine.Algorithm.ADAPTIVE) }
    var embedFileName by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var capacity by remember { mutableStateOf<Int?>(null) }

    // Recompute the cover's capacity whenever the cover or chosen algorithm changes.
    LaunchedEffect(coverUri, algorithm) {
        val uri = coverUri
        capacity = if (uri == null) null else withContext(Dispatchers.IO) {
            imageDimensions(context, uri)?.let { (w, h) ->
                runCatching { StegoEngine.capacityBytes(algorithm, w, h) }.getOrNull()
            }
        }
    }

    val openCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { coverUri = it }
    val openMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { messageUri = it }
    val openStego = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { stegoUri = it }

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    val saveStego = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
                    snackbar.showSnackbar("Saved stego image")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                } finally {
                    bytes.fill(0) // drop the produced image from the heap once written
                }
            }
        } else {
            bytes?.fill(0)
        }
    }
    val saveExtract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
                    snackbar.showSnackbar("Saved revealed file")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Error saving: ${e.message ?: e}")
                } finally {
                    bytes.fill(0) // wipe the decrypted plaintext from the heap once written
                }
            }
        } else {
            bytes?.fill(0)
        }
    }

    fun runHide() {
        val cover = coverUri
        val message = messageUri
        if (cover == null) { toast("Choose a cover image first"); return }
        if (message == null) { toast("Choose a file to hide first"); return }
        oversizeWarning(context, cover)?.let { toast(it); return }
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
                        pw
                    )
                }
                pendingBytes = bytes
                saveStego.launch("stego.png")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to hide data")
            } finally {
                pw?.fill(' ')
                busy = false
            }
        }
    }

    fun runReveal() {
        val stego = stegoUri
        if (stego == null) { toast("Choose a stego image first"); return }
        oversizeWarning(context, stego)?.let { toast(it); return }
        val pw = readPasswordChars(passwordView)
        busy = true
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    StegoEngine.extract(readBytes(context, stego), displayName(context, stego), pw)
                }
                pendingBytes = extracted.data
                saveExtract.launch(extracted.fileName.ifBlank { "revealed.dat" })
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed to reveal data")
            } finally {
                pw?.fill(' ')
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("NeoStego") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Hide") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Reveal") })
            }

            Text(
                if (tab == 0) "Hide a file inside an image" else "Reveal a hidden file from an image",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tab == 0) {
                FilePickCard(
                    label = "Cover image",
                    chosen = coverUri?.let { displayName(context, it) },
                    hint = "The image the data will be hidden in",
                    onPick = { openCover.launch(arrayOf("image/*")) }
                )
                capacity?.let {
                    Text(
                        "Can hide up to about ${humanBytes(it)} in this image",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilePickCard(
                    label = "File to hide",
                    chosen = messageUri?.let { displayName(context, it) },
                    hint = "Any file (document, photo, etc.)",
                    onPick = { openMessage.launch(arrayOf("*/*")) }
                )
            } else {
                FilePickCard(
                    label = "Stego image",
                    chosen = stegoUri?.let { displayName(context, it) },
                    hint = "An image that has data hidden in it",
                    onPick = { openStego.launch(arrayOf("image/*")) }
                )
            }

            SecurePasswordField(
                show = showPassword,
                onToggleShow = { showPassword = !showPassword },
                onViewCreated = { passwordView = it }
            )

            if (tab == 0) {
                Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Hiding method", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.ADAPTIVE,
                            title = "Adaptive (most secure)",
                            subtitle = "HILL + STC: hides changes in textured areas to resist both statistical and AI steganalysis. Lower capacity.",
                            onClick = { algorithm = StegoEngine.Algorithm.ADAPTIVE }
                        )
                        AlgorithmOption(
                            selected = algorithm == StegoEngine.Algorithm.MATCHING,
                            title = "Standard (LSB matching)",
                            subtitle = "Higher capacity and faster; resists classical steganalysis.",
                            onClick = { algorithm = StegoEngine.Algorithm.MATCHING }
                        )
                    }
                }
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Store original file name", fontWeight = FontWeight.SemiBold)
                            Text(
                                "The name is saved unencrypted. Leave off to keep it private; " +
                                    "the file is revealed with a generic name.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = embedFileName, onCheckedChange = { embedFileName = it })
                    }
                }
                Text(
                    "Keep the saved PNG as-is to share. Re-saving or sending it as JPEG (or any other " +
                        "lossy format) destroys the hidden data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { if (tab == 0) runHide() else runReveal() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Working...")
                } else {
                    Text(if (tab == 0) "Hide & Save" else "Reveal & Save")
                }
            }
        }
    }
}

@Composable
private fun AlgorithmOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    chosen ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onPick) { Text(if (chosen == null) "Choose" else "Change") }
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
 * that cannot be wiped), this lets the password be read out as a char[] and erased after use. The
 * IME is told not to learn the password and to disable suggestions/autocorrect.
 */
@Composable
private fun SecurePasswordField(
    show: Boolean,
    onToggleShow: () -> Unit,
    onViewCreated: (EditText) -> Unit
) {
    // A native EditText does not inherit Compose's color scheme, so pull the relevant theme colors
    // and apply them below (in update, so a light/dark switch is reflected live).
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Password (optional)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleShow) { Text(if (show) "Hide" else "Show") }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    EditText(ctx).apply {
                        setSingleLine(true)
                        // Password variation disables suggestions/autocorrect; the flag stops the IME
                        // from learning/storing the password
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        transformationMethod = PasswordTransformationMethod.getInstance()
                        onViewCreated(this)
                    }
                },
                update = { et ->
                    // Match the EditText to the active Compose theme so the text stays legible in dark mode
                    et.setTextColor(textColor)
                    et.setHintTextColor(hintColor)
                    et.highlightColor = accentColor
                    et.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
                    et.setSelection(et.text.length)
                }
            )
        }
    }
}
