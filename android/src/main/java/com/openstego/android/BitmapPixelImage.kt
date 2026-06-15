/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.android

import android.graphics.Bitmap
import com.openstego.desktop.image.PixelImage

/**
 * [PixelImage] backed by an Android [Bitmap] (must be mutable and ARGB_8888).
 */
class BitmapPixelImage(val bitmap: Bitmap) : PixelImage {
    override fun getWidth(): Int = bitmap.width

    override fun getHeight(): Int = bitmap.height

    override fun getRGB(x: Int, y: Int): Int = bitmap.getPixel(x, y)

    override fun setRGB(x: Int, y: Int, rgb: Int) {
        // Keep the pixel fully opaque; only the low 24 bits carry image data
        bitmap.setPixel(x, y, -0x1000000 or (rgb and 0x00FFFFFF))
    }
}
