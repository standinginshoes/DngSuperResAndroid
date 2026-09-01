package com.openai.dngsuperres;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_DNGS = 1001;
    private static final int SAVE_RESULT = 1002;
    private static final int PREVIEW_MAX = 384;
    private static final int SEARCH_RADIUS = 14;
    private static final int MAX_FRAMES = 30;
    private static final int DISPLAY_PREVIEW_MAX = 2048;
    private static final double MAX_GHOST_FRACTION = 0.22;
    private static final int PIXEL_GHOST_THRESHOLD = 36;
    private static final int FUSION_TILE_HEIGHT = 256;

    private final ArrayList<Uri> frames = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Bitmap resultBitmap;
    private volatile boolean cancelRequested;
    private Bitmap resultPreview;

    private TextView status;
    private ImageView previewImage;
    private ProgressBar progress;
    private Button processButton;
    private Button cancelButton;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DNG Super Resolution");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(20,20,20));
        title.setPadding(0, dp(8), 0, dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Offline 2× multi-frame stacker • MVP v0.1");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        Button select = new Button(this);
        select.setText("Select DNG burst");
        select.setOnClickListener(v -> pickDngs());
        root.addView(select, matchWrap());

        processButton = new Button(this);
        processButton.setText("Build 2× Super Resolution");
        processButton.setEnabled(false);
        processButton.setOnClickListener(v -> startProcessing());
        root.addView(processButton, matchWrap());

        cancelButton = new Button(this);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(v -> {
            cancelRequested = true;
            status.setText("Cancelling…");
            cancelButton.setEnabled(false);
        });
        root.addView(cancelButton, matchWrap());

        saveButton = new Button(this);
        saveButton.setText("Save TIFF");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> chooseSaveLocation());
        root.addView(saveButton, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = matchWrap();
        pp.topMargin = dp(18);
        root.addView(progress, pp);

        status = new TextView(this);
        status.setText("Select 2 or more DNG files.");
        status.setTextSize(17);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        previewImage = new ImageView(this);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setBackgroundColor(Color.rgb(238, 238, 238));
        previewImage.setContentDescription("Processed result preview");
        LinearLayout.LayoutParams imageParams = matchWrap();
        imageParams.height = dp(280);
        root.addView(previewImage, imageParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void pickDngs() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "image/*", "image/x-adobe-dng", "application/dng", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_DNGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == PICK_DNGS) {
            frames.clear();
            if (data.getClipData() != null) {
                int count = Math.min(data.getClipData().getItemCount(), MAX_FRAMES);
                for (int n = 0; n < count; n++) {
                    addFrame(data.getClipData().getItemAt(n).getUri());
                }
            } else if (data.getData() != null) {
                addFrame(data.getData());
            }
            Collections.sort(frames, Comparator.comparing(this::displayName));
                String suffix = data.getClipData() != null && data.getClipData().getItemCount() > MAX_FRAMES
                    ? " (first " + MAX_FRAMES + " used)" : "";
                status.setText(frames.size() + " frame(s) selected" + suffix);
            processButton.setEnabled(frames.size() >= 2);
            cancelButton.setEnabled(false);
            saveButton.setEnabled(false);
            resultBitmap = null;
            if (resultPreview != null && !resultPreview.isRecycled()) resultPreview.recycle();
            resultPreview = null;
            previewImage.setImageDrawable(null);
        } else if (requestCode == SAVE_RESULT && data.getData() != null) {
            saveResult(data.getData());
        }
    }

    private void addFrame(Uri uri) {
        if (uri == null || frames.contains(uri)) return;
        frames.add(uri);
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private void startProcessing() {
        final ArrayList<Uri> snapshot = new ArrayList<>(frames);
        processButton.setEnabled(false);
        cancelButton.setEnabled(true);
        saveButton.setEnabled(false);
        progress.setProgress(0);
        status.setText("Analyzing reference frame…");
        cancelRequested = false;

        executor.execute(() -> {
            try {
                List<FrameInfo> info = new ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    checkCancelled();
                    Bitmap preview = decodePreview(snapshot.get(i));
                    if (preview == null) throw new IllegalStateException("Could not decode " + displayName(snapshot.get(i)));
                    double sharpness = sharpness(preview);
                    info.add(new FrameInfo(snapshot.get(i), preview, sharpness));
                    updateProgress((int)(20.0 * (i + 1) / snapshot.size()), "Analyzing frame " + (i + 1) + "/" + snapshot.size());
                }

                FrameInfo ref = Collections.max(info, Comparator.comparingDouble(f -> f.sharpness));
                double referenceLuma = medianLuma(ref.preview);
                for (FrameInfo f : info) {
                    f.exposureScale = Math.max(0.75, Math.min(1.33,
                            referenceLuma / Math.max(1.0, medianLuma(f.preview))));
                }
                runOnUiThread(() -> status.setText("Reference: " + displayName(ref.uri) + " • aligning…"));

                for (int i = 0; i < info.size(); i++) {
                    checkCancelled();
                    FrameInfo f = info.get(i);
                    if (f == ref) {
                        f.dxPreview = 0;
                        f.dyPreview = 0;
                        f.alignmentCost = 0;
                    } else {
                        double[] shift = estimateShift(ref.preview, f.preview);
                        f.dxPreview = shift[0];
                        f.dyPreview = shift[1];
                        f.alignmentCost = shift[2];
                        f.ghostFraction = ghostFraction(ref.preview, f.preview, (int)Math.round(shift[0]),
                            (int)Math.round(shift[1]), f.exposureScale);
                    }
                    updateProgress(20 + (int)(30.0 * (i + 1) / info.size()), "Aligned " + (i + 1) + "/" + info.size());
                }

                Bitmap refFull = decodeFull(ref.uri);
                if (refFull == null) throw new IllegalStateException("Reference DNG could not be decoded at full resolution.");
                int w = refFull.getWidth();
                int h = refFull.getHeight();
                Bitmap out;
                try {
                    out = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError e) {
                    throw new IllegalStateException("Not enough memory for the " + (w * 2) + "×" + (h * 2) + " output. Use fewer or smaller DNGs.", e);
                }
                Canvas canvas = new Canvas(out);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

                ArrayList<FrameInfo> ordered = new ArrayList<>();
                ordered.add(ref);
                double[] costs = new double[Math.max(0, info.size() - 1)];
                int costIndex = 0;
                for (FrameInfo f : info) if (f != ref) costs[costIndex++] = f.alignmentCost;
                double medianCost = median(costs);
                double rejectCost = Math.max(medianCost * 2.0, medianCost + 8.0);
                for (FrameInfo f : info) {
                    if (f != ref && f.alignmentCost <= rejectCost && f.ghostFraction <= MAX_GHOST_FRACTION) ordered.add(f);
                }
                if (ordered.size() == 1 && info.size() > 1) {
                    FrameInfo best = Collections.min(info, Comparator.comparingDouble(f -> f.alignmentCost));
                    if (best != ref) ordered.add(best);
                }
                final int rejectedCount = info.size() - ordered.size();
                runOnUiThread(() -> status.setText("Stacking " + ordered.size() + " frames" +
                        (rejectedCount == 0 ? "" : " • rejected " + rejectedCount + " poor matches")));

                double totalWeight = 0.0;
                for (int i = 0; i < ordered.size(); i++) {
                    checkCancelled();
                    FrameInfo f = ordered.get(i);
                    Bitmap full = (f == ref) ? refFull : decodeFull(f.uri);
                    if (full == null) continue;
                    if (full.getWidth() != w || full.getHeight() != h) {
                        full.recycle();
                        continue;
                    }

                    float previewScaleX = (float)w / (float)f.preview.getWidth();
                    float previewScaleY = (float)h / (float)f.preview.getHeight();
                    float dxFull = (float)f.dxPreview * previewScaleX;
                    float dyFull = (float)f.dyPreview * previewScaleY;

                    // Down-weight soft frames while retaining a contribution from every capture.
                    double sharpnessRatio = f.sharpness / Math.max(ref.sharpness, 1e-9);
                    double sharpnessWeight = Math.max(0.35, Math.min(1.0, sharpnessRatio));
                    double alignmentWeight = Math.exp(-f.alignmentCost / 24.0);
                    double ghostWeight = 1.0 - Math.min(1.0, f.ghostFraction / MAX_GHOST_FRACTION);
                    double qualityWeight = sharpnessWeight * Math.max(0.15, Math.min(1.0, alignmentWeight)) * ghostWeight;
                    qualityWeight = Math.max(0.15, Math.min(1.0, qualityWeight));
                    double alpha = i == 0 ? 1.0 : qualityWeight / (totalWeight + qualityWeight);
                    paint.setAlpha(Math.max(1, Math.min(255, Math.round((float)(alpha * 255.0)))));
                    paint.setColorFilter(colorFilterFor(f.exposureScale));
                    canvas.save();
                    canvas.scale(2f, 2f);
                    canvas.translate(-dxFull, -dyFull);
                    if (i == 0) {
                        canvas.drawBitmap(full, 0f, 0f, paint);
                    } else {
                        drawGhostRejectedTiles(canvas, full, refFull, Math.round(dxFull), Math.round(dyFull), paint);
                    }
                    canvas.restore();
                    paint.setColorFilter(null);
                    totalWeight += qualityWeight;

                    if (full != refFull) full.recycle();
                    updateProgress(50 + (int)(48.0 * (i + 1) / ordered.size()), "Stacking " + (i + 1) + "/" + ordered.size());
                }

                for (FrameInfo f : info) f.preview.recycle();
                refFull.recycle();
                resultBitmap = out;
                runOnUiThread(() -> {
                    progress.setProgress(100);
                    status.setText("Done • " + out.getWidth() + "×" + out.getHeight() + " • " + ordered.size() + " frames");
                    resultPreview = makeDisplayPreview(out);
                    previewImage.setImageBitmap(resultPreview);
                    processButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                    saveButton.setEnabled(true);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText(t instanceof ProcessingCancelledException ? "Processing cancelled" : "Processing failed: " + t.getMessage());
                    processButton.setEnabled(true);
                    cancelButton.setEnabled(false);
                    saveButton.setEnabled(false);
                    if (!(t instanceof ProcessingCancelledException)) {
                        Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private Bitmap decodePreview(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            float scale = Math.min(1f, PREVIEW_MAX / (float)Math.max(w, h));
            decoder.setTargetSize(Math.max(32, Math.round(w * scale)), Math.max(32, Math.round(h * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
        });
    }

    private Bitmap decodeFull(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
        });
    }

    private double sharpness(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = 2;
        double sum = 0;
        long count = 0;
        for (int y = 2; y < h - 2; y += step) {
            for (int x = 2; x < w - 2; x += step) {
                int c = luma(b.getPixel(x,y));
                int lap = 4*c - luma(b.getPixel(x-1,y)) - luma(b.getPixel(x+1,y)) - luma(b.getPixel(x,y-1)) - luma(b.getPixel(x,y+1));
                sum += (double)lap * lap;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private double[] estimateShift(Bitmap ref, Bitmap img) {
        Bitmap candidate = img;
        if (img.getWidth() != ref.getWidth() || img.getHeight() != ref.getHeight()) {
            candidate = Bitmap.createScaledBitmap(img, ref.getWidth(), ref.getHeight(), true);
        }
        int bestX = 0, bestY = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy += 2) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += 2) {
                double s = shiftCost(ref, candidate, dx, dy, 4);
                if (s < best) { best = s; bestX = dx; bestY = dy; }
            }
        }
        int coarseX = bestX, coarseY = bestY;
        best = Double.POSITIVE_INFINITY;
        for (int dy = coarseY - 2; dy <= coarseY + 2; dy++) {
            for (int dx = coarseX - 2; dx <= coarseX + 2; dx++) {
                double s = shiftCost(ref, candidate, dx, dy, 2);
                if (s < best) { best = s; bestX = dx; bestY = dy; }
            }
        }
        double cxm = shiftCost(ref, candidate, bestX - 1, bestY, 2);
        double cx0 = shiftCost(ref, candidate, bestX, bestY, 2);
        double cxp = shiftCost(ref, candidate, bestX + 1, bestY, 2);
        double cym = shiftCost(ref, candidate, bestX, bestY - 1, 2);
        double cy0 = cx0;
        double cyp = shiftCost(ref, candidate, bestX, bestY + 1, 2);
        double subX = parabolicOffset(cxm, cx0, cxp);
        double subY = parabolicOffset(cym, cy0, cyp);
        if (candidate != img) candidate.recycle();
        return new double[]{bestX + subX, bestY + subY, best};
    }

    private double parabolicOffset(double left, double center, double right) {
        double denom = left - 2.0 * center + right;
        if (Math.abs(denom) < 1e-9) return 0;
        double v = 0.5 * (left - right) / denom;
        return Math.max(-0.75, Math.min(0.75, v));
    }

    private double shiftCost(Bitmap a, Bitmap b, int dx, int dy, int step) {
        int w = a.getWidth(), h = a.getHeight();
        int margin = SEARCH_RADIUS + 4;
        long total = 0;
        long count = 0;
        for (int y = margin + 1; y < h - margin; y += step) {
            int by = y + dy;
            if (by < 1 || by >= h) continue;
            for (int x = margin + 1; x < w - margin; x += step) {
                int bx = x + dx;
                if (bx < 1 || bx >= w) continue;
                int aCenter = luma(a.getPixel(x, y));
                int bCenter = luma(b.getPixel(bx, by));
                int aHorizontal = aCenter - luma(a.getPixel(x - 1, y));
                int bHorizontal = bCenter - luma(b.getPixel(bx - 1, by));
                int aVertical = aCenter - luma(a.getPixel(x, y - 1));
                int bVertical = bCenter - luma(b.getPixel(bx, by - 1));
                total += Math.abs(aHorizontal - bHorizontal);
                total += Math.abs(aVertical - bVertical);
                count++;
            }
        }
        return count == 0 ? Double.POSITIVE_INFINITY : (double)total / count;
    }

    private double ghostFraction(Bitmap reference, Bitmap image, int dx, int dy, double exposureScale) {
        int width = reference.getWidth();
        int height = reference.getHeight();
        int margin = SEARCH_RADIUS + 5;
        int changed = 0;
        int samples = 0;
        for (int y = margin + 1; y < height - margin; y += 3) {
            int imageY = y + dy;
            if (imageY < 1 || imageY >= height) continue;
            for (int x = margin + 1; x < width - margin; x += 3) {
                int imageX = x + dx;
                if (imageX < 1 || imageX >= width) continue;
                int referenceCenter = luma(reference.getPixel(x, y));
                int imageCenter = scaledLuma(image.getPixel(imageX, imageY), exposureScale);
                int referenceGradient = Math.abs(referenceCenter - luma(reference.getPixel(x - 1, y)))
                        + Math.abs(referenceCenter - luma(reference.getPixel(x, y - 1)));
                int imageGradient = Math.abs(imageCenter - scaledLuma(image.getPixel(imageX - 1, imageY), exposureScale))
                    + Math.abs(imageCenter - scaledLuma(image.getPixel(imageX, imageY - 1), exposureScale));
                if (Math.abs(referenceGradient - imageGradient) > 42
                        && Math.abs(referenceCenter - imageCenter) > 28) changed++;
                samples++;
            }
        }
        return samples == 0 ? 1.0 : (double)changed / samples;
    }

    private int scaledLuma(int color, double scale) {
        return Math.max(0, Math.min(255, (int)Math.round(luma(color) * scale)));
    }

    private double medianLuma(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleWidth = Math.max(1, width / 32);
        int sampleHeight = Math.max(1, height / 32);
        int[] values = new int[1024];
        int count = 0;
        for (int y = sampleHeight / 2; y < height && count < values.length; y += sampleHeight) {
            for (int x = sampleWidth / 2; x < width && count < values.length; x += sampleWidth) {
                values[count++] = luma(bitmap.getPixel(x, y));
            }
        }
        if (count == 0) return 1.0;
        java.util.Arrays.sort(values, 0, count);
        return values[count / 2];
    }

    private ColorMatrixColorFilter colorFilterFor(double scale) {
        float value = (float)scale;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                value, 0, 0, 0, 0,
                0, value, 0, 0, 0,
                0, 0, value, 0, 0,
                0, 0, 0, 1, 0
        }));
    }

    private void drawGhostRejectedTiles(Canvas canvas, Bitmap image, Bitmap reference, int dx, int dy, Paint paint) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] imagePixels = new int[width * FUSION_TILE_HEIGHT];
        int[] referenceRow = new int[width];
        for (int top = 0; top < height; top += FUSION_TILE_HEIGHT) {
            int tileHeight = Math.min(FUSION_TILE_HEIGHT, height - top);
            image.getPixels(imagePixels, 0, width, 0, top, width, tileHeight);
            for (int row = 0; row < tileHeight; row++) {
                int y = top + row;
                int referenceY = y - dy;
                if (referenceY < 0 || referenceY >= height) continue;
                reference.getPixels(referenceRow, 0, width, 0, referenceY, width, 1);
                for (int x = 0; x < width; x++) {
                    int referenceX = x - dx;
                    if (referenceX < 0 || referenceX >= width) continue;
                    int index = row * width + x;
                    int color = imagePixels[index];
                    int difference = Math.abs(luma(color) - luma(referenceRow[referenceX]));
                    int localWeight = difference <= PIXEL_GHOST_THRESHOLD ? 255
                            : Math.max(0, 255 - (difference - PIXEL_GHOST_THRESHOLD) * 8);
                    imagePixels[index] = Color.argb(localWeight, Color.red(color), Color.green(color), Color.blue(color));
                }
            }
            Bitmap tile = Bitmap.createBitmap(imagePixels, 0, width, width, tileHeight, Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(tile, 0f, top, paint);
            tile.recycle();
        }
    }

    private int luma(int c) {
        return (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) >> 8;
    }

    private void chooseSaveLocation() {
        if (resultBitmap == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/tiff");
        i.putExtra(Intent.EXTRA_TITLE, "SuperRes_2x.tif");
        startActivityForResult(i, SAVE_RESULT);
    }

    private void saveResult(Uri uri) {
        if (resultBitmap == null) return;
        executor.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("Could not open output file.");
                writeTiff(out, resultBitmap);
                runOnUiThread(() -> Toast.makeText(this, "Saved SuperRes_2x.tif", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "TIFF save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void writeTiff(OutputStream out, Bitmap bitmap) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] make = "Google\0".getBytes("US-ASCII");
        byte[] cameraModel = "Pixel 10 Pro\0".getBytes("US-ASCII");
        byte[] software = "DngSuperRes Android\0".getBytes("US-ASCII");
        byte[] model = "DngSuperRes RGB\0".getBytes("US-ASCII");
        int entryCount = 13;
        int ifdEnd = 8 + 2 + entryCount * 12 + 4;
        int bitsOffset = ifdEnd;
        int makeOffset = bitsOffset + 6;
        int cameraModelOffset = makeOffset + make.length;
        int softwareOffset = cameraModelOffset + cameraModel.length;
        int modelOffset = softwareOffset + software.length;
        int pixelOffset = modelOffset + model.length;
        long pixelBytes = (long)width * height * 3L;

        putShort(out, 'I' | ('I' << 8));
        putShort(out, 42);
        putInt(out, 8);
        putShort(out, entryCount);
        entry(out, 256, 4, 1, width);
        entry(out, 257, 4, 1, height);
        entry(out, 258, 3, 3, bitsOffset);
        entry(out, 259, 3, 1, 1);
        entry(out, 262, 3, 1, 2);
        entry(out, 271, 2, make.length, makeOffset);
        entry(out, 272, 2, cameraModel.length, cameraModelOffset);
        entry(out, 273, 4, 1, pixelOffset);
        entry(out, 277, 3, 1, 3);
        entry(out, 278, 4, 1, height);
        entry(out, 279, 4, 1, pixelBytes);
        entry(out, 284, 3, 1, 1);
        entry(out, 305, 2, software.length, softwareOffset);
        putInt(out, 0);
        putShort(out, 8);
        putShort(out, 8);
        putShort(out, 8);
        out.write(make);
        out.write(cameraModel);
        out.write(software);
        out.write(model);

        int[] pixels = new int[width];
        byte[] row = new byte[width * 3];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int index = x * 3;
                row[index] = (byte)Color.red(color);
                row[index + 1] = (byte)Color.green(color);
                row[index + 2] = (byte)Color.blue(color);
            }
            out.write(row);
        }
    }

    private void entry(OutputStream out, int tag, int type, int count, long value) throws IOException {
        putShort(out, tag);
        putShort(out, type);
        putInt(out, count);
        if (type == 1 && count <= 4) {
            putInt(out, value);
        } else if (type == 3 && count == 1) {
            putShort(out, (int)value);
            putShort(out, 0);
        } else {
            putInt(out, value);
        }
    }

    private void putShort(OutputStream out, int value) throws IOException {
        out.write(value & 255);
        out.write((value >> 8) & 255);
    }

    private void putInt(OutputStream out, long value) throws IOException {
        out.write((int)value & 255);
        out.write(((int)value >> 8) & 255);
        out.write(((int)value >> 16) & 255);
        out.write(((int)value >> 24) & 255);
    }

    private Bitmap makeDisplayPreview(Bitmap source) {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= DISPLAY_PREVIEW_MAX) return source;
        float scale = DISPLAY_PREVIEW_MAX / (float)largest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (resultBitmap != null && !resultBitmap.isRecycled()) resultBitmap.recycle();
        if (resultPreview != null && !resultPreview.isRecycled() && resultPreview != resultBitmap) resultPreview.recycle();
        super.onDestroy();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "frame" : uri.getLastPathSegment();
    }

    private void updateProgress(int value, String text) {
        runOnUiThread(() -> { progress.setProgress(value); status.setText(text); });
    }

    private void checkCancelled() throws ProcessingCancelledException {
        if (cancelRequested) throw new ProcessingCancelledException();
    }

    private double median(double[] values) {
        if (values.length == 0) return 0;
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class FrameInfo {
        final Uri uri;
        final Bitmap preview;
        final double sharpness;
        double dxPreview;
        double dyPreview;
        double alignmentCost;
        double ghostFraction;
        double exposureScale = 1.0;
        FrameInfo(Uri uri, Bitmap preview, double sharpness) {
            this.uri = uri; this.preview = preview; this.sharpness = sharpness;
        }
    }

    private static final class ProcessingCancelledException extends Exception {
    }
}
