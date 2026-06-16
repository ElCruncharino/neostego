/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.elcruncharino.neostego

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoErrors
import com.openstego.desktop.OpenStegoException
import com.openstego.desktop.image.ImageCodec
import com.openstego.desktop.image.PixelImage
import com.openstego.desktop.util.ExifUtil
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Android [ImageCodec] backed by [Bitmap] / [BitmapFactory]. Data-hiding output is always lossless PNG
 * (required to preserve the embedded bits); the watermarking path may request JPEG output (a robust
 * watermark survives lossy compression) via a `.jpg`/`.jpeg` output name and [jpegQuality].
 */
class BitmapImageCodec : ImageCodec {

    companion object {
        private const val DEFAULT_JPEG_QUALITY = 90
    }

    /** Quality (1-100) used when the output name is a JPEG. Only the watermarking path sets this. */
    @Volatile
    var jpegQuality: Int = DEFAULT_JPEG_QUALITY

    /** Restore the default JPEG quality after a watermark embed. */
    fun resetJpegQuality() {
        jpegQuality = DEFAULT_JPEG_QUALITY
    }

    override fun decode(data: ByteArray, fileName: String?): PixelImage {
        // Decode bit-exactly: no density scaling, no dithering, no alpha premultiplication and no
        // colour-space conversion. Any of these would alter the least-significant bits and make
        // previously embedded data unreadable.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inDither = false
            inMutable = true
            inPremultiplied = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.IMAGE_FILE_INVALID, fileName)
        // BitmapFactory ignores the Exif orientation tag, so a portrait photo stored landscape-with-orientation
        // would embed sideways and the stego output would look rotated relative to the original (pixels
        // otherwise identical). Rotate upright here; the transform is an exact 90°/flip remap (filter = false),
        // so no interpolation touches the pixels. Our own PNG output carries no orientation tag, so extract
        // re-decodes it as orientation 1 — a no-op — keeping embed and extract in agreement.
        val orientation = ExifUtil.readExifOrientation(data)
        if (orientation != 1) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, orientationMatrix(orientation), false)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) {
            val converted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()
            bitmap = converted
        }
        return BitmapPixelImage(bitmap)
    }

    /** Build the affine transform that rotates/flips a bitmap upright for the given Exif orientation (2-8). */
    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)                       // mirror horizontal
            3 -> setRotate(180f)                         // rotate 180
            4 -> setScale(1f, -1f)                       // mirror vertical
            5 -> { setRotate(90f); postScale(-1f, 1f) }  // transpose
            6 -> setRotate(90f)                          // rotate 90 CW
            7 -> { setRotate(-90f); postScale(-1f, 1f) } // transverse
            8 -> setRotate(-90f)                         // rotate 90 CCW
        }
    }

    override fun encode(image: PixelImage, fileName: String?): ByteArray {
        val bitmap = (image as BitmapPixelImage).bitmap
        val out = ByteArrayOutputStream(32 * 1024)
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        if (ext == "jpg" || ext == "jpeg") {
            // Robust watermark output. JPEG cannot store alpha, so Bitmap drops it (transparent areas
            // composite to black). Data hiding never requests this path - it always outputs PNG.
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
        } else {
            // PNG is lossless, which is required to preserve the embedded bits
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }

    override fun createRandomImage(numOfPixels: Int): PixelImage {
        val aspect = 4.0 / 3.0
        val width = Math.ceil(Math.sqrt(numOfPixels * aspect)).toInt()
        val height = Math.ceil(numOfPixels / width.toDouble()).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = SecureRandom()
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            pixels[i] = -0x1000000 or (random.nextInt() and 0x00FFFFFF)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return BitmapPixelImage(bitmap)
    }

    override fun getReadableFormats(): List<String> = listOf("png", "jpg", "jpeg", "bmp", "webp", "gif")

    override fun getWritableFormats(): List<String> = listOf("png")
}
