/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import com.openstego.desktop.OpenStego
import com.openstego.desktop.OpenStegoErrors
import com.openstego.desktop.OpenStegoException
import com.openstego.desktop.image.ImageCodec
import com.openstego.desktop.image.PixelImage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Android [ImageCodec] backed by [Bitmap] / [BitmapFactory]. Output is always written as lossless PNG
 * (required for steganography).
 */
class BitmapImageCodec : ImageCodec {

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
        if (bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable) {
            val converted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()
            bitmap = converted
        }
        return BitmapPixelImage(bitmap)
    }

    override fun encode(image: PixelImage, fileName: String?): ByteArray {
        val bitmap = (image as BitmapPixelImage).bitmap
        val out = ByteArrayOutputStream(32 * 1024)
        // PNG is lossless, which is required to preserve the embedded bits
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
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
