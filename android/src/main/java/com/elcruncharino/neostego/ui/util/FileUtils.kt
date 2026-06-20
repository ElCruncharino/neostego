/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** A produced output (stego/watermarked image, revealed file, or signature) ready to save or share. */
data class OutputResult(val name: String, val mime: String, val bytes: ByteArray)

internal fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("Unable to read the selected file")

internal fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IllegalStateException("Unable to write to the selected location")
}

internal fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment ?: "file"
}

/** MIME type inferred from a file name's extension; used to tag shared/saved output. */
internal fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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
internal fun buildShareIntent(context: Context, name: String, mime: String, bytes: ByteArray): Intent {
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
