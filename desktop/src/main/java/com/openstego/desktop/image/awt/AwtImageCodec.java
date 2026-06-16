/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image.awt;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoErrors;
import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodec;
import com.openstego.desktop.image.PixelImage;
import com.openstego.desktop.util.ExifUtil;
import com.openstego.desktop.util.ImageHolder;
import com.openstego.desktop.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

/**
 * Desktop {@link ImageCodec} implementation backed by AWT / ImageIO. Discovered via
 * {@link java.util.ServiceLoader}.
 */
public class AwtImageCodec implements ImageCodec {
    private static List<String> readFormats = null;
    private static List<String> writeFormats = null;

    @Override
    public PixelImage decode(byte[] data, String fileName) throws OpenStegoException {
        ImageHolder holder = ImageUtil.byteArrayToImage(data, fileName);
        BufferedImage image = holder.getImage();
        // Honor the Exif/eXIf orientation tag (which ImageIO ignores) and normalize to TYPE_INT_ARGB in one
        // pass. Without this, a cover shot in portrait but stored landscape-with-orientation embeds sideways
        // and the stego output looks rotated relative to the original (pixels otherwise identical). The
        // remap also gives us the consistent ARGB surface per-pixel get/set wants while preserving alpha
        // (embedding only touches RGB bits 0-23, so any transparency survives the round-trip unchanged).
        int orientation = ExifUtil.readExifOrientation(data);
        if (orientation != 1 || image.getType() != BufferedImage.TYPE_INT_ARGB) {
            image = ImageUtil.applyExifOrientation(image, orientation);
        }
        return new BufferedImagePixelImage(image);
    }

    @Override
    public byte[] encode(PixelImage image, String fileName) throws OpenStegoException {
        BufferedImage bufferedImage = ((BufferedImagePixelImage) image).getBufferedImage();
        return ImageUtil.imageToByteArray(new ImageHolder(bufferedImage, null), fileName, getWritableFormats());
    }

    @Override
    public PixelImage createRandomImage(int numOfPixels) throws OpenStegoException {
        ImageHolder holder = ImageUtil.generateRandomImage(numOfPixels);
        return new BufferedImagePixelImage(holder.getImage());
    }

    @Override
    public List<String> getReadableFormats() {
        if (readFormats == null) {
            readFormats = collectFormats(ImageIO.getReaderFormatNames());
        }
        return readFormats;
    }

    @Override
    public List<String> getWritableFormats() {
        if (writeFormats != null) {
            return writeFormats;
        }

        // Steganography requires lossless output, so keep only losslessly-writable formats
        List<String> formats = collectFormats(ImageIO.getWriterFormatNames());
        for (int i = formats.size() - 1; i >= 0; i--) {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersBySuffix(formats.get(i));
            while (iter.hasNext()) {
                ImageWriteParam writeParam = iter.next().getDefaultWriteParam();
                try {
                    writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    String[] compTypes = writeParam.getCompressionTypes();
                    if (compTypes != null && compTypes.length > 0) {
                        writeParam.setCompressionType(compTypes[0]);
                    }
                } catch (UnsupportedOperationException uoEx) {
                    break; // Compression not supported
                }
                if (writeParam.isCompressionLossless()) {
                    break;
                }
                formats.remove(i);
            }
        }

        // Explicitly remove formats with unsupported color models or known issues
        formats.remove("gif");
        formats.remove("wbmp");
        formats.remove("tif");
        formats.remove("tiff");

        writeFormats = formats;
        return writeFormats;
    }

    private static List<String> collectFormats(String[] formats) {
        List<String> result = new ArrayList<>();
        for (String s : formats) {
            String format = s.toLowerCase();
            if (format.contains("jpeg") && format.contains("2000")) {
                format = "jp2";
            }
            if (!result.contains(format)) {
                result.add(format);
            }
        }
        Collections.sort(result);
        return result;
    }
}
