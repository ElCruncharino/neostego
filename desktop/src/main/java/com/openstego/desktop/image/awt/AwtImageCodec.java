/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.image.awt;

import com.openstego.desktop.OpenStegoException;
import com.openstego.desktop.image.ImageCodec;
import com.openstego.desktop.image.PixelImage;
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
        // byteArrayToImage already normalizes Exif orientation (the shared decode funnel), so pixels arrive
        // upright here. Normalize to TYPE_INT_ARGB so per-pixel get/set behaves consistently across source
        // types and the alpha channel is preserved. Embedding only touches the RGB channels (bits 0-23), so
        // any transparency in the cover survives the embed/extract round-trip unchanged.
        ImageHolder holder = ImageUtil.byteArrayToImage(data, fileName);
        BufferedImage image = holder.getImage();
        if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            argb.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
            image = argb;
        }
        BufferedImagePixelImage pixelImage = new BufferedImagePixelImage(image);
        // Carry the cover's ICC profile through the embed so the stego output keeps it (issue #62).
        pixelImage.setIccProfile(holder.getIccProfile());
        return pixelImage;
    }

    @Override
    public byte[] encode(PixelImage image, String fileName) throws OpenStegoException {
        BufferedImagePixelImage pixelImage = (BufferedImagePixelImage) image;
        ImageHolder holder = new ImageHolder(pixelImage.getBufferedImage(), null);
        holder.setIccProfile(pixelImage.getIccProfile());
        // Validate against everything we can physically write (which includes lossy JPEG), not just the
        // lossless formats advertised for data hiding: watermarking legitimately outputs JPEG. The
        // lossless-only policy for data hiding is enforced by the data-hiding plugins advertising only
        // lossless extensions, so they never request a lossy output here.
        return ImageUtil.imageToByteArray(holder, fileName, getEncodableFormats());
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

    /**
     * Formats this codec can physically write, including lossy ones (e.g. JPEG) used by watermarking. This is
     * broader than {@link #getWritableFormats()} (which is the lossless-only set advertised for data hiding):
     * it only drops formats with color-model issues that ImageIO cannot reliably encode.
     *
     * @return list of encodable format suffixes
     */
    private static List<String> getEncodableFormats() {
        List<String> formats = collectFormats(ImageIO.getWriterFormatNames());
        formats.remove("gif");
        formats.remove("wbmp");
        formats.remove("tif");
        formats.remove("tiff");
        return formats;
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
