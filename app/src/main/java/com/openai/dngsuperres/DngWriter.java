package com.openai.dngsuperres;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the rendered merge as an uncompressed 16-bit LinearRaw DNG. */
final class DngWriter {
    private static final int TYPE_BYTE = 1;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_SRATIONAL = 10;
    private static final int ENTRY_COUNT = 22;
    private static final int[] SRGB_TO_LINEAR_16 = buildSrgbToLinearTable();

    private DngWriter() { }

    static void write(OutputStream out, Bitmap bitmap) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long pixelBytes = (long)width * height * 6L;
        if (pixelBytes > 0xffffffffL) {
            throw new IOException("DNG output is too large for a classic TIFF/DNG container.");
        }

        byte[] make = ascii("DngSuperRes");
        byte[] model = ascii("Merged RGB burst");
        byte[] software = ascii("DngSuperRes Android 0.8");
        byte[] uniqueModel = ascii("DngSuperRes LinearRaw");

        int ifdEnd = 8 + 2 + ENTRY_COUNT * 12 + 4;
        ByteArrayOutputStream metadata = new ByteArrayOutputStream(160);

        int bitsOffset = appendShorts(metadata, ifdEnd, 16, 16, 16);
        int sampleFormatOffset = appendShorts(metadata, ifdEnd, 1, 1, 1);
        int makeOffset = appendBytes(metadata, ifdEnd, make);
        int modelOffset = appendBytes(metadata, ifdEnd, model);
        int softwareOffset = appendBytes(metadata, ifdEnd, software);
        int uniqueModelOffset = appendBytes(metadata, ifdEnd, uniqueModel);

        // D65 XYZ-to-linear-sRGB matrix. LinearRaw pixels use these RGB primaries.
        int colorMatrixOffset = ifdEnd + metadata.size();
        int[] matrix = {
                3240454, -1537139, -498531,
                -969266, 1876011, 41556,
                55643, -204026, 1057225
        };
        for (int value : matrix) {
            putInt(metadata, value);
            putInt(metadata, 1_000_000);
        }
        int neutralOffset = ifdEnd + metadata.size();
        for (int i = 0; i < 3; i++) {
            putInt(metadata, 1);
            putInt(metadata, 1);
        }
        padEven(metadata);
        int pixelOffset = ifdEnd + metadata.size();

        putShort(out, 'I' | ('I' << 8));
        putShort(out, 42);
        putInt(out, 8);
        putShort(out, ENTRY_COUNT);
        entry(out, 254, TYPE_LONG, 1, 0);                  // NewSubfileType
        entry(out, 256, TYPE_LONG, 1, width);              // ImageWidth
        entry(out, 257, TYPE_LONG, 1, height);             // ImageLength
        entry(out, 258, TYPE_SHORT, 3, bitsOffset);        // BitsPerSample
        entry(out, 259, TYPE_SHORT, 1, 1);                 // Compression: none
        entry(out, 262, TYPE_SHORT, 1, 34892);             // LinearRaw
        entry(out, 271, TYPE_ASCII, make.length, makeOffset);
        entry(out, 272, TYPE_ASCII, model.length, modelOffset);
        entry(out, 273, TYPE_LONG, 1, pixelOffset);        // StripOffsets
        entry(out, 274, TYPE_SHORT, 1, 1);                 // Orientation
        entry(out, 277, TYPE_SHORT, 1, 3);                 // SamplesPerPixel
        entry(out, 278, TYPE_LONG, 1, height);             // RowsPerStrip
        entry(out, 279, TYPE_LONG, 1, pixelBytes);         // StripByteCounts
        entry(out, 284, TYPE_SHORT, 1, 1);                 // Chunky RGB
        entry(out, 305, TYPE_ASCII, software.length, softwareOffset);
        entry(out, 339, TYPE_SHORT, 3, sampleFormatOffset);
        entry(out, 50706, TYPE_BYTE, 4, 1 | (6 << 8));     // DNGVersion 1.6.0.0
        entry(out, 50707, TYPE_BYTE, 4, 1 | (4 << 8));     // DNGBackwardVersion 1.4.0.0
        entry(out, 50708, TYPE_ASCII, uniqueModel.length, uniqueModelOffset);
        entry(out, 50721, TYPE_SRATIONAL, 9, colorMatrixOffset);
        entry(out, 50728, TYPE_RATIONAL, 3, neutralOffset); // AsShotNeutral
        entry(out, 50778, TYPE_SHORT, 1, 21);               // D65
        // LinearRaw defaults are black=0 and white=65535.
        putInt(out, 0);
        out.write(metadata.toByteArray());

        int[] pixels = new int[width];
        byte[] row = new byte[width * 6];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int index = x * 6;
                putU16(row, index, SRGB_TO_LINEAR_16[Color.red(color)]);
                putU16(row, index + 2, SRGB_TO_LINEAR_16[Color.green(color)]);
                putU16(row, index + 4, SRGB_TO_LINEAR_16[Color.blue(color)]);
            }
            out.write(row);
        }
    }

    private static int appendShorts(ByteArrayOutputStream out, int baseOffset, int... values)
            throws IOException {
        int offset = baseOffset + out.size();
        for (int value : values) putShort(out, value);
        padEven(out);
        return offset;
    }

    private static int appendBytes(ByteArrayOutputStream out, int baseOffset, byte[] values) {
        int offset = baseOffset + out.size();
        out.write(values, 0, values.length);
        padEven(out);
        return offset;
    }

    private static void padEven(ByteArrayOutputStream out) {
        if ((out.size() & 1) != 0) out.write(0);
    }

    private static byte[] ascii(String value) {
        byte[] text = value.getBytes(StandardCharsets.US_ASCII);
        byte[] terminated = new byte[text.length + 1];
        System.arraycopy(text, 0, terminated, 0, text.length);
        return terminated;
    }

    private static void entry(OutputStream out, int tag, int type, int count, long value)
            throws IOException {
        putShort(out, tag);
        putShort(out, type);
        putInt(out, count);
        if (type == TYPE_SHORT && count == 1) {
            putShort(out, (int)value);
            putShort(out, 0);
        } else {
            putInt(out, value);
        }
    }

    private static void putU16(byte[] target, int offset, int value) {
        target[offset] = (byte)(value & 255);
        target[offset + 1] = (byte)((value >>> 8) & 255);
    }

    private static void putShort(OutputStream out, int value) throws IOException {
        out.write(value & 255);
        out.write((value >>> 8) & 255);
    }

    private static void putInt(OutputStream out, long value) throws IOException {
        out.write((int)value & 255);
        out.write(((int)value >>> 8) & 255);
        out.write(((int)value >>> 16) & 255);
        out.write(((int)value >>> 24) & 255);
    }

    private static int[] buildSrgbToLinearTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            double encoded = i / 255.0;
            double linear = encoded <= 0.04045
                    ? encoded / 12.92
                    : Math.pow((encoded + 0.055) / 1.055, 2.4);
            table[i] = (int)Math.round(linear * 65535.0);
        }
        return table;
    }
}
