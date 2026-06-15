/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 * Based on OpenStego by Samir Vaidya (mailto:syvaidya@gmail.com)
 */

package com.openstego.desktop.image.jpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.openstego.desktop.image.PixelImage;

/**
 * A pure-Java baseline (sequential Huffman) JPEG coefficient codec. It reads a JPEG into quantized
 * DCT coefficients ({@link #decode}), writes quantized coefficients back to JPEG bytes
 * ({@link #encode}), and turns an uncompressed precover into quantized coefficients while retaining
 * the rounding-error side information ({@link #fromPrecover}).
 * <p>
 * The decode/encode pair is lossless at the coefficient level: it parses the entropy stream straight
 * to coefficients and writes them straight back, with no DCT or re-quantization in between, so an
 * edit to a coefficient survives a re-encode byte-exactly. The DCT is used only on the precover
 * path. Scope is deliberately narrow &mdash; baseline sequential JPEG, a single interleaved scan,
 * 8-bit precision, 4:4:4 and 4:2:0 chroma. Progressive and arithmetic-coded streams are rejected.
 */
public final class JpegCodec {

    private static final int[] ZZ = JpegTables.ZIGZAG;

    // Standard Huffman tables used for writing.
    private static final HuffTable ENC_DC_LUMA =
            new HuffTable(JpegTables.STD_DC_LUMA_BITS, JpegTables.STD_DC_LUMA_VAL);
    private static final HuffTable ENC_AC_LUMA =
            new HuffTable(JpegTables.STD_AC_LUMA_BITS, JpegTables.STD_AC_LUMA_VAL);
    private static final HuffTable ENC_DC_CHROMA =
            new HuffTable(JpegTables.STD_DC_CHROMA_BITS, JpegTables.STD_DC_CHROMA_VAL);
    private static final HuffTable ENC_AC_CHROMA =
            new HuffTable(JpegTables.STD_AC_CHROMA_BITS, JpegTables.STD_AC_CHROMA_VAL);

    private JpegCodec() {
        // Static utility
    }

    // ------------------------------------------------------------------ decode

    /**
     * Decodes a baseline JPEG into quantized DCT coefficients.
     *
     * @param jpeg JPEG file bytes
     * @return the decoded image as quantized coefficients (no side information)
     * @throws IOException if the stream is not a supported baseline JPEG or is corrupt
     */
    public static JpegImage decode(byte[] jpeg) throws IOException {
        if (jpeg.length < 2 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("Not a JPEG (missing SOI)");
        }

        int[][] quantTables = new int[4][];
        HuffTable[] dc = new HuffTable[4];
        HuffTable[] ac = new HuffTable[4];
        int restartInterval = 0;

        int width = 0;
        int height = 0;
        JpegImage.Component[] comps = null;
        int maxH = 1;
        int maxV = 1;
        int mcuCols = 0;
        int mcuRows = 0;
        int[][][] coeff = null;

        int pos = 2;
        int len = jpeg.length;
        while (pos + 1 < len) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                pos++;
                continue;
            }
            int marker = jpeg[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xD9) { // EOI
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers (TEM/RSTn), no payload
            }

            int segLen = readU16(jpeg, pos);
            int content = pos + 2;
            int segEnd = pos + segLen;

            switch (marker) {
                case 0xDB: // DQT
                    parseDqt(jpeg, content, segEnd, quantTables);
                    break;
                case 0xC4: // DHT
                    parseDht(jpeg, content, segEnd, dc, ac);
                    break;
                case 0xDD: // DRI
                    restartInterval = readU16(jpeg, content);
                    break;
                case 0xC0: { // SOF0 baseline
                    int precision = jpeg[content] & 0xFF;
                    if (precision != 8) {
                        throw new IOException("Unsupported JPEG precision: " + precision);
                    }
                    height = readU16(jpeg, content + 1);
                    width = readU16(jpeg, content + 3);
                    int nf = jpeg[content + 5] & 0xFF;
                    comps = new JpegImage.Component[nf];
                    int p = content + 6;
                    maxH = 1;
                    maxV = 1;
                    for (int i = 0; i < nf; i++) {
                        JpegImage.Component c = new JpegImage.Component();
                        c.id = jpeg[p] & 0xFF;
                        int hv = jpeg[p + 1] & 0xFF;
                        c.hSamp = hv >> 4;
                        c.vSamp = hv & 0xF;
                        c.quantTableId = jpeg[p + 2] & 0xFF;
                        comps[i] = c;
                        maxH = Math.max(maxH, c.hSamp);
                        maxV = Math.max(maxV, c.vSamp);
                        p += 3;
                    }
                    mcuCols = ceilDiv(width, 8 * maxH);
                    mcuRows = ceilDiv(height, 8 * maxV);
                    coeff = new int[nf][][];
                    for (int i = 0; i < nf; i++) {
                        comps[i].blocksWide = mcuCols * comps[i].hSamp;
                        comps[i].blocksHigh = mcuRows * comps[i].vSamp;
                        coeff[i] = new int[comps[i].blocksWide * comps[i].blocksHigh][64];
                    }
                    break;
                }
                case 0xC1:
                case 0xC2:
                case 0xC3:
                case 0xC5:
                case 0xC6:
                case 0xC7:
                case 0xC9:
                case 0xCA:
                case 0xCB:
                case 0xCD:
                case 0xCE:
                case 0xCF:
                    throw new IOException("Unsupported JPEG mode (only baseline supported): marker 0x"
                            + Integer.toHexString(marker));
                case 0xDA: { // SOS
                    if (comps == null) {
                        throw new IOException("Corrupt JPEG: SOS before SOF");
                    }
                    int ns = jpeg[content] & 0xFF;
                    if (ns != comps.length) {
                        throw new IOException("Unsupported JPEG: non-interleaved scan");
                    }
                    int[] frameIdx = new int[ns];
                    int[] dcSel = new int[ns];
                    int[] acSel = new int[ns];
                    int p = content + 1;
                    for (int i = 0; i < ns; i++) {
                        int cs = jpeg[p] & 0xFF;
                        int tdta = jpeg[p + 1] & 0xFF;
                        dcSel[i] = tdta >> 4;
                        acSel[i] = tdta & 0xF;
                        int fi = -1;
                        for (int j = 0; j < comps.length; j++) {
                            if (comps[j].id == cs) {
                                fi = j;
                                break;
                            }
                        }
                        if (fi < 0) {
                            throw new IOException("Corrupt JPEG: scan component not in frame");
                        }
                        frameIdx[i] = fi;
                        p += 2;
                    }
                    int entropyStart = segEnd;
                    int entropyEnd = findScanEnd(jpeg, entropyStart);
                    decodeScan(jpeg, entropyStart, entropyEnd, comps, coeff, dc, ac,
                            frameIdx, dcSel, acSel, mcuCols, mcuRows, restartInterval);
                    pos = entropyEnd;
                    continue; // pos already advanced past entropy data
                }
                default:
                    break; // APPn, COM, etc. — skip via segLen
            }
            pos = segEnd;
        }

        if (comps == null || coeff == null) {
            throw new IOException("Corrupt JPEG: no frame header");
        }
        return new JpegImage(width, height, comps, quantTables, maxH, maxV, mcuCols, mcuRows,
                coeff, null);
    }

    private static void parseDqt(byte[] d, int pos, int end, int[][] quantTables) {
        while (pos < end) {
            int pqTq = d[pos++] & 0xFF;
            int pq = pqTq >> 4;
            int tq = pqTq & 0xF;
            int[] table = new int[64];
            if (pq == 0) {
                for (int k = 0; k < 64; k++) {
                    table[ZZ[k]] = d[pos++] & 0xFF;
                }
            } else {
                for (int k = 0; k < 64; k++) {
                    table[ZZ[k]] = readU16(d, pos);
                    pos += 2;
                }
            }
            quantTables[tq] = table;
        }
    }

    private static void parseDht(byte[] d, int pos, int end, HuffTable[] dc, HuffTable[] ac) {
        while (pos < end) {
            int tcTh = d[pos++] & 0xFF;
            int tc = tcTh >> 4;
            int th = tcTh & 0xF;
            int[] bits = new int[16];
            int total = 0;
            for (int i = 0; i < 16; i++) {
                bits[i] = d[pos++] & 0xFF;
                total += bits[i];
            }
            int[] vals = new int[total];
            for (int i = 0; i < total; i++) {
                vals[i] = d[pos++] & 0xFF;
            }
            HuffTable ht = new HuffTable(bits, vals);
            if (tc == 0) {
                dc[th] = ht;
            } else {
                ac[th] = ht;
            }
        }
    }

    private static void decodeScan(byte[] d, int start, int end, JpegImage.Component[] comps,
            int[][][] coeff, HuffTable[] dc, HuffTable[] ac, int[] frameIdx, int[] dcSel,
            int[] acSel, int mcuCols, int mcuRows, int restartInterval) throws IOException {
        BitReader in = new BitReader(d, start, end);
        int[] pred = new int[comps.length];
        int ns = frameIdx.length;
        long mcuCount = 0;
        for (int my = 0; my < mcuRows; my++) {
            for (int mx = 0; mx < mcuCols; mx++) {
                if (restartInterval > 0 && mcuCount > 0 && mcuCount % restartInterval == 0) {
                    in.restart();
                    java.util.Arrays.fill(pred, 0);
                }
                for (int si = 0; si < ns; si++) {
                    int fc = frameIdx[si];
                    JpegImage.Component c = comps[fc];
                    HuffTable dcT = dc[dcSel[si]];
                    HuffTable acT = ac[acSel[si]];
                    for (int by = 0; by < c.vSamp; by++) {
                        for (int bx = 0; bx < c.hSamp; bx++) {
                            int br = my * c.vSamp + by;
                            int bc = mx * c.hSamp + bx;
                            int[] block = coeff[fc][br * c.blocksWide + bc];
                            decodeBlock(in, dcT, acT, block, pred, fc);
                        }
                    }
                }
                mcuCount++;
            }
        }
    }

    private static void decodeBlock(BitReader in, HuffTable dcT, HuffTable acT, int[] block,
            int[] pred, int fc) throws IOException {
        int s = dcT.decode(in);
        int diff = (s == 0) ? 0 : extend(in.readBits(s), s);
        pred[fc] += diff;
        block[0] = pred[fc];
        int zz = 1;
        while (zz < 64) {
            int rs = acT.decode(in);
            int r = rs >> 4;
            int sz = rs & 0xF;
            if (sz == 0) {
                if (r == 15) {
                    zz += 16; // ZRL: 16 zeros
                    continue;
                }
                break; // EOB
            }
            zz += r;
            if (zz > 63) {
                break;
            }
            block[ZZ[zz]] = extend(in.readBits(sz), sz);
            zz++;
        }
    }

    // ------------------------------------------------------------------ encode

    /**
     * Encodes quantized DCT coefficients to a baseline JPEG byte stream, writing standard Annex-K
     * Huffman tables and the image's preserved quantization tables. No DCT or re-quantization is
     * performed, so coefficient edits are reproduced exactly.
     *
     * @param img the image to encode
     * @return JPEG file bytes (JFIF)
     */
    public static byte[] encode(JpegImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JpegImage.Component[] comps = img.components();

        // SOI
        out.write(0xFF);
        out.write(0xD8);

        writeJfifApp0(out);
        writeDqt(out, img);
        writeSof0(out, img);
        writeDht(out, comps.length);
        writeSosAndEntropy(out, img);

        // EOI
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static void writeJfifApp0(ByteArrayOutputStream out) {
        out.write(0xFF);
        out.write(0xE0);
        writeU16(out, 16);
        out.write('J');
        out.write('F');
        out.write('I');
        out.write('F');
        out.write(0);
        out.write(1); // version major
        out.write(1); // version minor
        out.write(0); // units: none
        writeU16(out, 1); // X density
        writeU16(out, 1); // Y density
        out.write(0); // X thumbnail
        out.write(0); // Y thumbnail
    }

    private static void writeDqt(ByteArrayOutputStream out, JpegImage img) {
        int[][] tables = img.quantTables();
        JpegImage.Component[] comps = img.components();
        boolean[] used = new boolean[4];
        for (JpegImage.Component c : comps) {
            used[c.quantTableId] = true;
        }
        int len = 2;
        for (int id = 0; id < 4; id++) {
            if (used[id] && tables[id] != null) {
                len += 1 + 64;
            }
        }
        out.write(0xFF);
        out.write(0xDB);
        writeU16(out, len);
        for (int id = 0; id < 4; id++) {
            if (used[id] && tables[id] != null) {
                out.write(id); // Pq=0 (8-bit) | Tq=id
                for (int k = 0; k < 64; k++) {
                    out.write(tables[id][ZZ[k]] & 0xFF);
                }
            }
        }
    }

    private static void writeSof0(ByteArrayOutputStream out, JpegImage img) {
        JpegImage.Component[] comps = img.components();
        int nf = comps.length;
        out.write(0xFF);
        out.write(0xC0);
        writeU16(out, 8 + 3 * nf);
        out.write(8); // precision
        writeU16(out, img.getHeight());
        writeU16(out, img.getWidth());
        out.write(nf);
        for (JpegImage.Component c : comps) {
            out.write(c.id);
            out.write((c.hSamp << 4) | c.vSamp);
            out.write(c.quantTableId);
        }
    }

    private static void writeDht(ByteArrayOutputStream out, int numComponents) {
        out.write(0xFF);
        out.write(0xC4);
        boolean color = numComponents > 1;
        int len = 2;
        len += 1 + 16 + JpegTables.STD_DC_LUMA_VAL.length;
        len += 1 + 16 + JpegTables.STD_AC_LUMA_VAL.length;
        if (color) {
            len += 1 + 16 + JpegTables.STD_DC_CHROMA_VAL.length;
            len += 1 + 16 + JpegTables.STD_AC_CHROMA_VAL.length;
        }
        writeU16(out, len);
        writeHuffSpec(out, 0x00, JpegTables.STD_DC_LUMA_BITS, JpegTables.STD_DC_LUMA_VAL);
        writeHuffSpec(out, 0x10, JpegTables.STD_AC_LUMA_BITS, JpegTables.STD_AC_LUMA_VAL);
        if (color) {
            writeHuffSpec(out, 0x01, JpegTables.STD_DC_CHROMA_BITS, JpegTables.STD_DC_CHROMA_VAL);
            writeHuffSpec(out, 0x11, JpegTables.STD_AC_CHROMA_BITS, JpegTables.STD_AC_CHROMA_VAL);
        }
    }

    private static void writeHuffSpec(ByteArrayOutputStream out, int tcTh, int[] bits, int[] vals) {
        out.write(tcTh);
        for (int i = 0; i < 16; i++) {
            out.write(bits[i]);
        }
        for (int v : vals) {
            out.write(v);
        }
    }

    private static void writeSosAndEntropy(ByteArrayOutputStream out, JpegImage img) {
        JpegImage.Component[] comps = img.components();
        int ns = comps.length;
        out.write(0xFF);
        out.write(0xDA);
        writeU16(out, 6 + 2 * ns);
        out.write(ns);
        for (int i = 0; i < ns; i++) {
            out.write(comps[i].id);
            int sel = (i == 0) ? 0x00 : 0x11; // luma tables for comp 0, chroma for the rest
            out.write(sel);
        }
        out.write(0); // Ss
        out.write(63); // Se
        out.write(0); // Ah/Al

        BitWriter bw = new BitWriter(out);
        int[] pred = new int[ns];
        int mcuCols = img.mcuCols();
        int mcuRows = img.mcuRows();
        for (int my = 0; my < mcuRows; my++) {
            for (int mx = 0; mx < mcuCols; mx++) {
                for (int si = 0; si < ns; si++) {
                    JpegImage.Component c = comps[si];
                    HuffTable dcT = (si == 0) ? ENC_DC_LUMA : ENC_DC_CHROMA;
                    HuffTable acT = (si == 0) ? ENC_AC_LUMA : ENC_AC_CHROMA;
                    int[][] blocks = img.coeff(si);
                    for (int by = 0; by < c.vSamp; by++) {
                        for (int bx = 0; bx < c.hSamp; bx++) {
                            int br = my * c.vSamp + by;
                            int bc = mx * c.hSamp + bx;
                            encodeBlock(bw, dcT, acT, blocks[br * c.blocksWide + bc], pred, si);
                        }
                    }
                }
            }
        }
        bw.pad();
    }

    private static void encodeBlock(BitWriter bw, HuffTable dcT, HuffTable acT, int[] block,
            int[] pred, int si) {
        int diff = block[0] - pred[si];
        pred[si] = block[0];
        int s = category(diff);
        bw.writeBits(dcT.codeOf(s), dcT.sizeOf(s));
        if (s > 0) {
            bw.writeBits(diff < 0 ? diff + (1 << s) - 1 : diff, s);
        }
        int run = 0;
        for (int zz = 1; zz < 64; zz++) {
            int v = block[ZZ[zz]];
            if (v == 0) {
                run++;
                continue;
            }
            while (run > 15) {
                bw.writeBits(acT.codeOf(0xF0), acT.sizeOf(0xF0)); // ZRL
                run -= 16;
            }
            int sz = category(v);
            int sym = (run << 4) | sz;
            bw.writeBits(acT.codeOf(sym), acT.sizeOf(sym));
            bw.writeBits(v < 0 ? v + (1 << sz) - 1 : v, sz);
            run = 0;
        }
        if (run > 0) {
            bw.writeBits(acT.codeOf(0x00), acT.sizeOf(0x00)); // EOB
        }
    }

    // -------------------------------------------------------------- fromPrecover

    /**
     * Builds quantized coefficients from an uncompressed precover, retaining rounding-error side
     * information. Uses 4:2:0 chroma subsampling (the common, natural format).
     *
     * @param image   the uncompressed cover (RGB)
     * @param quality JPEG quality factor 1..100
     * @return quantized coefficients with side information ({@link JpegImage#hasSideInfo()} true)
     */
    public static JpegImage fromPrecover(PixelImage image, int quality) {
        return fromPrecover(image, quality, true);
    }

    /**
     * Builds quantized coefficients from an uncompressed precover, retaining rounding-error side
     * information.
     *
     * @param image     the uncompressed cover (RGB)
     * @param quality   JPEG quality factor 1..100
     * @param subsample {@code true} for 4:2:0 chroma, {@code false} for 4:4:4
     * @return quantized coefficients with side information
     */
    public static JpegImage fromPrecover(PixelImage image, int quality, boolean subsample) {
        int w = image.getWidth();
        int h = image.getHeight();

        // Full-resolution YCbCr planes (BT.601).
        double[][] yP = new double[h][w];
        double[][] cbP = new double[h][w];
        double[][] crP = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                yP[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
                cbP[y][x] = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0;
                crP[y][x] = 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0;
            }
        }

        int[] lumaQ = JpegTables.scaleQuant(JpegTables.STD_LUMA_QUANT, quality);
        int[] chromaQ = JpegTables.scaleQuant(JpegTables.STD_CHROMA_QUANT, quality);
        int[][] quantTables = new int[4][];
        quantTables[0] = lumaQ;
        quantTables[1] = chromaQ;

        int maxH = subsample ? 2 : 1;
        int maxV = subsample ? 2 : 1;
        int mcuCols = ceilDiv(w, 8 * maxH);
        int mcuRows = ceilDiv(h, 8 * maxV);

        JpegImage.Component[] comps = new JpegImage.Component[3];
        for (int i = 0; i < 3; i++) {
            comps[i] = new JpegImage.Component();
            comps[i].id = i + 1;
        }
        comps[0].hSamp = maxH;
        comps[0].vSamp = maxV;
        comps[0].quantTableId = 0;
        comps[1].hSamp = 1;
        comps[1].vSamp = 1;
        comps[1].quantTableId = 1;
        comps[2].hSamp = 1;
        comps[2].vSamp = 1;
        comps[2].quantTableId = 1;
        for (int i = 0; i < 3; i++) {
            comps[i].blocksWide = mcuCols * comps[i].hSamp;
            comps[i].blocksHigh = mcuRows * comps[i].vSamp;
        }

        int[][][] coeff = new int[3][][];
        double[][][] rounding = new double[3][][];

        // Chroma plane dimensions (downsampled if subsampling).
        int cw = subsample ? ceilDiv(w, 2) : w;
        int ch = subsample ? ceilDiv(h, 2) : h;
        double[][] cb = subsample ? downsample(cbP, w, h, cw, ch) : cbP;
        double[][] cr = subsample ? downsample(crP, w, h, cw, ch) : crP;

        coeff[0] = transformPlane(yP, h, w, comps[0].blocksWide, comps[0].blocksHigh, lumaQ,
                rounding, 0);
        coeff[1] = transformPlane(cb, ch, cw, comps[1].blocksWide, comps[1].blocksHigh, chromaQ,
                rounding, 1);
        coeff[2] = transformPlane(cr, ch, cw, comps[2].blocksWide, comps[2].blocksHigh, chromaQ,
                rounding, 2);

        return new JpegImage(w, h, comps, quantTables, maxH, maxV, mcuCols, mcuRows, coeff, rounding);
    }

    /** Averages a plane down by 2x2 (with edge clamping) to {@code cw x ch}. */
    private static double[][] downsample(double[][] src, int w, int h, int cw, int ch) {
        double[][] out = new double[ch][cw];
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int x0 = Math.min(2 * cx, w - 1);
                int x1 = Math.min(2 * cx + 1, w - 1);
                int y0 = Math.min(2 * cy, h - 1);
                int y1 = Math.min(2 * cy + 1, h - 1);
                out[cy][cx] = (src[y0][x0] + src[y0][x1] + src[y1][x0] + src[y1][x1]) / 4.0;
            }
        }
        return out;
    }

    /**
     * Forward-DCT-and-quantize every block of a sample plane, writing the rounding errors into
     * {@code rounding[compIdx]}.
     */
    private static int[][] transformPlane(double[][] plane, int planeH, int planeW, int blocksWide,
            int blocksHigh, int[] quant, double[][][] rounding, int compIdx) {
        int nBlocks = blocksWide * blocksHigh;
        int[][] coeff = new int[nBlocks][64];
        double[][] err = new double[nBlocks][64];
        double[] samples = new double[64];
        double[] u = new double[64];
        for (int br = 0; br < blocksHigh; br++) {
            for (int bc = 0; bc < blocksWide; bc++) {
                for (int yy = 0; yy < 8; yy++) {
                    int sy = Math.min(br * 8 + yy, planeH - 1);
                    for (int xx = 0; xx < 8; xx++) {
                        int sx = Math.min(bc * 8 + xx, planeW - 1);
                        samples[yy * 8 + xx] = plane[sy][sx];
                    }
                }
                Dct8x8.forward(samples, u);
                int idx = br * blocksWide + bc;
                int[] q = coeff[idx];
                double[] e = err[idx];
                for (int k = 0; k < 64; k++) {
                    double uq = u[k] / quant[k];
                    int rounded = (int) Math.floor(uq + 0.5);
                    q[k] = rounded;
                    e[k] = uq - rounded; // in [-0.5, 0.5)
                }
            }
        }
        rounding[compIdx] = err;
        return coeff;
    }

    // ------------------------------------------------------------------ helpers

    /** Sign-extends an {@code s}-bit magnitude code to a signed coefficient (T.81 EXTEND). */
    private static int extend(int v, int s) {
        return (v < (1 << (s - 1))) ? v - (1 << s) + 1 : v;
    }

    /** @return the JPEG magnitude category (number of bits) for a coefficient value. */
    private static int category(int v) {
        int a = Math.abs(v);
        int s = 0;
        while (a > 0) {
            a >>= 1;
            s++;
        }
        return s;
    }

    private static int readU16(byte[] d, int pos) {
        return ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * Finds the offset of the marker that terminates an entropy segment: scans past stuffed bytes
     * ({@code 0xFF 0x00}), restart markers ({@code 0xFF 0xD0..0xD7}) and fill bytes.
     */
    private static int findScanEnd(byte[] d, int start) {
        int i = start;
        while (i + 1 < d.length) {
            if ((d[i] & 0xFF) == 0xFF) {
                int m = d[i + 1] & 0xFF;
                if (m == 0x00 || (m >= 0xD0 && m <= 0xD7)) {
                    i += 2;
                    continue;
                }
                if (m == 0xFF) {
                    i++; // fill byte
                    continue;
                }
                return i;
            }
            i++;
        }
        return d.length;
    }
}
