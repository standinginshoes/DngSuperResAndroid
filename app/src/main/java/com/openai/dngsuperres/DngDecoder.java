package com.openai.dngsuperres;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal decoder for uncompressed Bayer DNG files, including MotionCam packed 10-bit DNGs. */
final class DngDecoder {
    private static final int TIFF_MAGIC = 42;
    private static final int PHOTOMETRIC_CFA = 32803;
    private static final int COMPRESSION_NONE = 1;
    private static final int MAX_INPUT_BYTES = 256 * 1024 * 1024;

    private static final int TAG_NEW_SUBFILE_TYPE = 254;
    private static final int TAG_WIDTH = 256;
    private static final int TAG_HEIGHT = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC = 262;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TAG_SUB_IFDS = 330;
    private static final int TAG_CFA_REPEAT = 33421;
    private static final int TAG_CFA_PATTERN = 33422;
    private static final int TAG_BLACK_REPEAT = 50713;
    private static final int TAG_BLACK_LEVEL = 50714;
    private static final int TAG_WHITE_LEVEL = 50717;
    private static final int TAG_COLOR_MATRIX_1 = 50721;
    private static final int TAG_COLOR_MATRIX_2 = 50722;
    private static final int TAG_AS_SHOT_NEUTRAL = 50728;
    private static final int TAG_FORWARD_MATRIX_1 = 50964;
    private static final int TAG_FORWARD_MATRIX_2 = 50965;

    // XYZ D50 to linear sRGB (includes chromatic adaptation).
    private static final float[] XYZ_D50_TO_SRGB = {
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f
    };

    private DngDecoder() { }

    static Bitmap decode(ContentResolver resolver, Uri uri, int maxDimension) throws IOException {
        byte[] bytes = readAll(resolver, uri);
        Tiff tiff = new Tiff(bytes);
        RawIfd raw = tiff.findRawIfd();
        if (raw.compression != COMPRESSION_NONE) {
            throw new IOException("This DNG uses unsupported compression " + raw.compression
                    + ". This build currently supports uncompressed Bayer DNGs.");
        }
        if (raw.cfaWidth != 2 || raw.cfaHeight != 2 || raw.cfaPattern.length < 4) {
            throw new IOException("This DNG uses an unsupported CFA layout. A standard 2×2 Bayer pattern is required.");
        }

        short[] mosaic = unpackMosaic(bytes, tiff.littleEndian, raw);
        return render(raw, mosaic, maxDimension);
    }

    private static byte[] readAll(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Could not open the selected file.");
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_INPUT_BYTES) throw new IOException("DNG is larger than the 256 MB safety limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static short[] unpackMosaic(byte[] bytes, boolean littleEndian, RawIfd raw) throws IOException {
        long pixelCountLong = (long)raw.width * raw.height;
        if (pixelCountLong <= 0 || pixelCountLong > Integer.MAX_VALUE) {
            throw new IOException("DNG dimensions are too large.");
        }
        short[] mosaic;
        try {
            mosaic = new short[(int)pixelCountLong];
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory to unpack this DNG.", error);
        }

        int bits = raw.bitsPerSample;
        if (bits < 8 || bits > 16) throw new IOException("Unsupported DNG bit depth: " + bits);
        long rowBytesLong = ((long)raw.width * bits + 7L) / 8L;
        if (rowBytesLong > Integer.MAX_VALUE) throw new IOException("DNG row is too large.");
        int rowBytes = (int)rowBytesLong;

        for (int y = 0; y < raw.height; y++) {
            int strip = Math.min(raw.stripOffsets.length - 1, y / raw.rowsPerStrip);
            int rowInStrip = y - strip * raw.rowsPerStrip;
            long rowOffsetLong = raw.stripOffsets[strip] + (long)rowInStrip * rowBytes;
            if (rowOffsetLong < 0 || rowOffsetLong + rowBytes > bytes.length) {
                throw new IOException("DNG pixel strip is truncated.");
            }
            int rowOffset = (int)rowOffsetLong;
            int destination = y * raw.width;
            if (bits == 16) {
                for (int x = 0; x < raw.width; x++) {
                    int offset = rowOffset + x * 2;
                    int value = littleEndian
                            ? (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                            : ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
                    mosaic[destination + x] = (short)value;
                }
            } else if (bits == 8) {
                for (int x = 0; x < raw.width; x++) mosaic[destination + x] = (short)(bytes[rowOffset + x] & 255);
            } else {
                int mask = (1 << bits) - 1;
                int rowEnd = rowOffset + rowBytes;
                for (int x = 0; x < raw.width; x++) {
                    int bit = x * bits;
                    int offset = rowOffset + (bit >>> 3);
                    int bitInByte = bit & 7;
                    int packed = (bytes[offset] & 255) << 16;
                    if (offset + 1 < rowEnd) packed |= (bytes[offset + 1] & 255) << 8;
                    if (offset + 2 < rowEnd) packed |= bytes[offset + 2] & 255;
                    int shift = 24 - bitInByte - bits;
                    mosaic[destination + x] = (short)((packed >>> shift) & mask);
                }
            }
        }
        return mosaic;
    }

    private static Bitmap render(RawIfd raw, short[] mosaic, int maxDimension) throws IOException {
        float scale = maxDimension <= 0 ? 1f
                : Math.min(1f, maxDimension / (float)Math.max(raw.width, raw.height));
        int outputWidth = Math.max(1, Math.round(raw.width * scale));
        int outputHeight = Math.max(1, Math.round(raw.height * scale));
        Bitmap output;
        try {
            output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory to render this DNG.", error);
        }

        float[] neutral = raw.asShotNeutral;
        float redBalance = neutral.length >= 3 && neutral[0] > 0f ? neutral[1] / neutral[0] : 1f;
        float blueBalance = neutral.length >= 3 && neutral[2] > 0f ? neutral[1] / neutral[2] : 1f;
        float[] rgbMatrix = multiply3x3(XYZ_D50_TO_SRGB, raw.cameraToXyz);
        int[] row = new int[outputWidth];

        for (int outputY = 0; outputY < outputHeight; outputY++) {
            int sourceY = Math.min(raw.height - 1,
                    Math.max(0, (int)((outputY + 0.5f) * raw.height / outputHeight)));
            for (int outputX = 0; outputX < outputWidth; outputX++) {
                int sourceX = Math.min(raw.width - 1,
                        Math.max(0, (int)((outputX + 0.5f) * raw.width / outputWidth)));
                int centerColor = raw.cfaColor(sourceX, sourceY);
                float red = centerColor == 0
                        ? raw.normalized(mosaic, sourceX, sourceY)
                        : averageColor(raw, mosaic, sourceX, sourceY, 0);
                float green = centerColor == 1
                        ? raw.normalized(mosaic, sourceX, sourceY)
                        : averageColor(raw, mosaic, sourceX, sourceY, 1);
                float blue = centerColor == 2
                        ? raw.normalized(mosaic, sourceX, sourceY)
                        : averageColor(raw, mosaic, sourceX, sourceY, 2);

                red *= redBalance;
                blue *= blueBalance;
                float linearRed = rgbMatrix[0] * red + rgbMatrix[1] * green + rgbMatrix[2] * blue;
                float linearGreen = rgbMatrix[3] * red + rgbMatrix[4] * green + rgbMatrix[5] * blue;
                float linearBlue = rgbMatrix[6] * red + rgbMatrix[7] * green + rgbMatrix[8] * blue;
                row[outputX] = Color.rgb(toSrgb8(linearRed), toSrgb8(linearGreen), toSrgb8(linearBlue));
            }
            output.setPixels(row, 0, outputWidth, 0, outputY, outputWidth, 1);
        }
        return output;
    }

    private static float averageColor(RawIfd raw, short[] mosaic, int x, int y, int wantedColor) {
        float sum = 0f;
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int sampleY = y + dy;
            if (sampleY < 0 || sampleY >= raw.height) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int sampleX = x + dx;
                if (sampleX < 0 || sampleX >= raw.width || raw.cfaColor(sampleX, sampleY) != wantedColor) continue;
                sum += raw.normalized(mosaic, sampleX, sampleY);
                count++;
            }
        }
        return count == 0 ? raw.normalized(mosaic, x, y) : sum / count;
    }

    private static int toSrgb8(float linear) {
        float value = Math.max(0f, Math.min(1f, linear));
        float encoded = value <= 0.0031308f
                ? 12.92f * value
                : 1.055f * (float)Math.pow(value, 1.0 / 2.4) - 0.055f;
        return Math.max(0, Math.min(255, Math.round(encoded * 255f)));
    }

    private static float[] multiply3x3(float[] a, float[] b) {
        float[] result = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                float value = 0f;
                for (int k = 0; k < 3; k++) value += a[row * 3 + k] * b[k * 3 + column];
                result[row * 3 + column] = value;
            }
        }
        return result;
    }

    private static float[] invert3x3(float[] m) throws IOException {
        float determinant = m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        if (Math.abs(determinant) < 1e-8f) throw new IOException("DNG color matrix is singular.");
        float inverse = 1f / determinant;
        return new float[]{
                (m[4] * m[8] - m[5] * m[7]) * inverse,
                (m[2] * m[7] - m[1] * m[8]) * inverse,
                (m[1] * m[5] - m[2] * m[4]) * inverse,
                (m[5] * m[6] - m[3] * m[8]) * inverse,
                (m[0] * m[8] - m[2] * m[6]) * inverse,
                (m[2] * m[3] - m[0] * m[5]) * inverse,
                (m[3] * m[7] - m[4] * m[6]) * inverse,
                (m[1] * m[6] - m[0] * m[7]) * inverse,
                (m[0] * m[4] - m[1] * m[3]) * inverse
        };
    }

    private static final class RawIfd {
        final int width;
        final int height;
        final int bitsPerSample;
        final int compression;
        final long[] stripOffsets;
        final long[] stripByteCounts;
        final int rowsPerStrip;
        final int cfaWidth;
        final int cfaHeight;
        final int[] cfaPattern;
        final int blackWidth;
        final int blackHeight;
        final float[] blackLevel;
        final float whiteLevel;
        final float[] asShotNeutral;
        final float[] cameraToXyz;

        RawIfd(Ifd ifd) throws IOException {
            width = ifd.requiredInt(TAG_WIDTH);
            height = ifd.requiredInt(TAG_HEIGHT);
            bitsPerSample = ifd.requiredInt(TAG_BITS_PER_SAMPLE);
            compression = ifd.intValue(TAG_COMPRESSION, COMPRESSION_NONE);
            stripOffsets = ifd.requiredLongArray(TAG_STRIP_OFFSETS);
            stripByteCounts = ifd.requiredLongArray(TAG_STRIP_BYTE_COUNTS);
            rowsPerStrip = Math.max(1, ifd.intValue(TAG_ROWS_PER_STRIP, height));
            int[] cfaRepeat = ifd.intArray(TAG_CFA_REPEAT, new int[]{2, 2});
            cfaHeight = cfaRepeat.length > 0 ? cfaRepeat[0] : 2;
            cfaWidth = cfaRepeat.length > 1 ? cfaRepeat[1] : 2;
            cfaPattern = ifd.requiredIntArray(TAG_CFA_PATTERN);
            int[] blackRepeat = ifd.intArray(TAG_BLACK_REPEAT, cfaRepeat);
            blackHeight = blackRepeat.length > 0 ? blackRepeat[0] : cfaHeight;
            blackWidth = blackRepeat.length > 1 ? blackRepeat[1] : cfaWidth;
            blackLevel = ifd.floatArray(TAG_BLACK_LEVEL, new float[]{0f});
            float[] white = ifd.floatArray(TAG_WHITE_LEVEL, new float[]{(1 << Math.min(bitsPerSample, 16)) - 1f});
            whiteLevel = white.length == 0 ? (1 << Math.min(bitsPerSample, 16)) - 1f : white[0];
            asShotNeutral = ifd.floatArray(TAG_AS_SHOT_NEUTRAL, new float[]{1f, 1f, 1f});

            float[] forward = ifd.floatArray(TAG_FORWARD_MATRIX_2, null);
            if (forward == null || forward.length < 9) forward = ifd.floatArray(TAG_FORWARD_MATRIX_1, null);
            if (forward != null && forward.length >= 9) {
                cameraToXyz = copyMatrix(forward);
            } else {
                float[] color = ifd.floatArray(TAG_COLOR_MATRIX_2, null);
                if (color == null || color.length < 9) color = ifd.floatArray(TAG_COLOR_MATRIX_1, null);
                if (color == null || color.length < 9) throw new IOException("DNG has no usable color matrix.");
                cameraToXyz = invert3x3(copyMatrix(color));
            }
        }

        int cfaColor(int x, int y) {
            return cfaPattern[(y % cfaHeight) * cfaWidth + (x % cfaWidth)];
        }

        float normalized(short[] mosaic, int x, int y) {
            int value = mosaic[y * width + x] & 0xffff;
            int blackIndex = (y % blackHeight) * blackWidth + (x % blackWidth);
            float black = blackLevel[Math.min(blackIndex, blackLevel.length - 1)];
            return Math.max(0f, (value - black) / Math.max(1f, whiteLevel - black));
        }

        private static float[] copyMatrix(float[] values) {
            float[] matrix = new float[9];
            System.arraycopy(values, 0, matrix, 0, 9);
            return matrix;
        }
    }

    private static final class Tiff {
        final byte[] bytes;
        final boolean littleEndian;
        final int firstIfd;

        Tiff(byte[] bytes) throws IOException {
            this.bytes = bytes;
            if (bytes.length < 8) throw new IOException("Selected file is not a valid DNG/TIFF.");
            if (bytes[0] == 'I' && bytes[1] == 'I') littleEndian = true;
            else if (bytes[0] == 'M' && bytes[1] == 'M') littleEndian = false;
            else throw new IOException("Selected file is not a valid DNG/TIFF.");
            if (u16(2) != TIFF_MAGIC) throw new IOException("Unsupported TIFF header.");
            firstIfd = checkedOffset(u32(4));
        }

        RawIfd findRawIfd() throws IOException {
            List<Ifd> ifds = new ArrayList<>();
            collectIfds(firstIfd, ifds, new HashSet<>(), 0);
            Ifd best = null;
            long bestPixels = -1;
            for (Ifd ifd : ifds) {
                if (ifd.intValue(TAG_PHOTOMETRIC, -1) != PHOTOMETRIC_CFA) continue;
                long pixels = (long)ifd.intValue(TAG_WIDTH, 0) * ifd.intValue(TAG_HEIGHT, 0);
                if (pixels > bestPixels) {
                    best = ifd;
                    bestPixels = pixels;
                }
            }
            if (best == null) throw new IOException("No Bayer CFA image was found in this DNG.");
            return new RawIfd(best);
        }

        private void collectIfds(int offset, List<Ifd> result, Set<Integer> seen, int depth) throws IOException {
            if (offset == 0 || depth > 16 || !seen.add(offset)) return;
            Ifd ifd = new Ifd(this, offset);
            result.add(ifd);
            Entry subIfds = ifd.entries.get(TAG_SUB_IFDS);
            if (subIfds != null) {
                for (long child : subIfds.longValues()) collectIfds(checkedOffset(child), result, seen, depth + 1);
            }
            if (ifd.nextIfd != 0) collectIfds(ifd.nextIfd, result, seen, depth + 1);
        }

        int u16(int offset) throws IOException {
            check(offset, 2);
            return littleEndian
                    ? (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                    : ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
        }

        long u32(int offset) throws IOException {
            check(offset, 4);
            if (littleEndian) {
                return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                        | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
            }
            return ((bytes[offset] & 255L) << 24) | ((bytes[offset + 1] & 255L) << 16)
                    | ((bytes[offset + 2] & 255L) << 8) | (bytes[offset + 3] & 255L);
        }

        int s32(int offset) throws IOException {
            return (int)u32(offset);
        }

        int checkedOffset(long value) throws IOException {
            if (value < 0 || value > Integer.MAX_VALUE || value >= bytes.length) {
                throw new IOException("DNG contains an invalid offset.");
            }
            return (int)value;
        }

        void check(int offset, long length) throws IOException {
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IOException("DNG metadata is truncated.");
            }
        }
    }

    private static final class Ifd {
        final Tiff tiff;
        final Map<Integer, Entry> entries = new HashMap<>();
        final int nextIfd;

        Ifd(Tiff tiff, int offset) throws IOException {
            this.tiff = tiff;
            int count = tiff.u16(offset);
            tiff.check(offset + 2, (long)count * 12 + 4);
            for (int i = 0; i < count; i++) {
                int entryOffset = offset + 2 + i * 12;
                Entry entry = new Entry(tiff, entryOffset);
                entries.put(entry.tag, entry);
            }
            long next = tiff.u32(offset + 2 + count * 12);
            nextIfd = next == 0 ? 0 : tiff.checkedOffset(next);
        }

        int requiredInt(int tag) throws IOException {
            int[] values = requiredIntArray(tag);
            return values[0];
        }

        int intValue(int tag, int fallback) throws IOException {
            Entry entry = entries.get(tag);
            if (entry == null) return fallback;
            long[] values = entry.longValues();
            return values.length == 0 ? fallback : (int)values[0];
        }

        int[] requiredIntArray(int tag) throws IOException {
            Entry entry = entries.get(tag);
            if (entry == null) throw new IOException("DNG is missing required tag " + tag + ".");
            long[] source = entry.longValues();
            if (source.length == 0) throw new IOException("DNG tag " + tag + " is empty.");
            int[] result = new int[source.length];
            for (int i = 0; i < source.length; i++) result[i] = (int)source[i];
            return result;
        }

        int[] intArray(int tag, int[] fallback) throws IOException {
            return entries.containsKey(tag) ? requiredIntArray(tag) : fallback;
        }

        long[] requiredLongArray(int tag) throws IOException {
            Entry entry = entries.get(tag);
            if (entry == null) throw new IOException("DNG is missing required tag " + tag + ".");
            return entry.longValues();
        }

        float[] floatArray(int tag, float[] fallback) throws IOException {
            Entry entry = entries.get(tag);
            return entry == null ? fallback : entry.floatValues();
        }
    }

    private static final class Entry {
        final Tiff tiff;
        final int entryOffset;
        final int tag;
        final int type;
        final int count;
        final int dataOffset;
        final int typeSize;

        Entry(Tiff tiff, int entryOffset) throws IOException {
            this.tiff = tiff;
            this.entryOffset = entryOffset;
            tag = tiff.u16(entryOffset);
            type = tiff.u16(entryOffset + 2);
            long countLong = tiff.u32(entryOffset + 4);
            if (countLong > Integer.MAX_VALUE) throw new IOException("DNG tag is too large.");
            count = (int)countLong;
            typeSize = sizeOf(type);
            long byteCount = (long)count * typeSize;
            dataOffset = byteCount <= 4 ? entryOffset + 8 : tiff.checkedOffset(tiff.u32(entryOffset + 8));
            tiff.check(dataOffset, byteCount);
        }

        long[] longValues() throws IOException {
            long[] result = new long[count];
            for (int i = 0; i < count; i++) {
                int offset = dataOffset + i * typeSize;
                switch (type) {
                    case 1:
                    case 6:
                    case 7:
                        result[i] = tiff.bytes[offset] & 255;
                        break;
                    case 3:
                    case 8:
                        result[i] = tiff.u16(offset);
                        break;
                    case 4:
                    case 9:
                    case 13:
                        result[i] = tiff.u32(offset);
                        break;
                    default:
                        throw new IOException("DNG tag " + tag + " has a non-integer type " + type + ".");
                }
            }
            return result;
        }

        float[] floatValues() throws IOException {
            float[] result = new float[count];
            for (int i = 0; i < count; i++) {
                int offset = dataOffset + i * typeSize;
                switch (type) {
                    case 1:
                    case 3:
                    case 4:
                        result[i] = longValues()[i];
                        break;
                    case 5: {
                        long denominator = tiff.u32(offset + 4);
                        result[i] = denominator == 0 ? 0f : tiff.u32(offset) / (float)denominator;
                        break;
                    }
                    case 10: {
                        int denominator = tiff.s32(offset + 4);
                        result[i] = denominator == 0 ? 0f : tiff.s32(offset) / (float)denominator;
                        break;
                    }
                    case 11: {
                        int bits = tiff.s32(offset);
                        result[i] = Float.intBitsToFloat(bits);
                        break;
                    }
                    case 12: {
                        long bits;
                        if (tiff.littleEndian) {
                            bits = tiff.u32(offset) | (tiff.u32(offset + 4) << 32);
                        } else {
                            bits = (tiff.u32(offset) << 32) | tiff.u32(offset + 4);
                        }
                        result[i] = (float)Double.longBitsToDouble(bits);
                        break;
                    }
                    default:
                        throw new IOException("DNG tag " + tag + " has unsupported type " + type + ".");
                }
            }
            return result;
        }

        private static int sizeOf(int type) throws IOException {
            switch (type) {
                case 1:
                case 2:
                case 6:
                case 7:
                    return 1;
                case 3:
                case 8:
                    return 2;
                case 4:
                case 9:
                case 11:
                case 13:
                    return 4;
                case 5:
                case 10:
                case 12:
                    return 8;
                default:
                    throw new IOException("Unsupported TIFF field type " + type + ".");
            }
        }
    }
}
