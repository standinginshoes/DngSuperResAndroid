package com.openai.dngsuperres;

import android.app.Activity;
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
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
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
    private static final int PREVIEW_MAX = 512;
    private static final int SEARCH_RADIUS = 18;
    private static final int MOTION_GRID_SIZE = 5;
    private static final int LOCAL_SEARCH_RADIUS = 7;
    private static final int MAX_FRAMES = 30;
    private static final int DISPLAY_PREVIEW_MAX = 2048;
    private static final double MAX_GHOST_FRACTION = 0.22;
    private static final int PIXEL_GHOST_THRESHOLD = 14;
    private static final int FUSION_TILE_SIZE = 512;
    private static final int RAW_MERGE_STRIPE_ROWS = 64;
    private static final long MAX_OUTPUT_PIXELS = 70_000_000L;
    private static final int OUTPUT_SCALE_STEPS = 20;
    private static final float OUTPUT_SCALE_STEP = 0.05f;

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
    private Button selectButton;
    private SeekBar resolutionSeekBar;
    private TextView resolutionValue;
    private Spinner formatSpinner;
    private float selectedOutputScale = 1f;
    private float resultOutputScale = 1f;
    private OutputFormat selectedOutputFormat = OutputFormat.TIFF;
    private OutputFormat pendingSaveFormat = OutputFormat.TIFF;

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
        subtitle.setText("Offline Bayer-domain stacking + super resolution • v0.8");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        selectButton = new Button(this);
        selectButton.setText("Select DNG burst");
        selectButton.setOnClickListener(v -> pickDngs());
        root.addView(selectButton, matchWrap());

        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText("Output resolution");
        resolutionLabel.setTextSize(16);
        resolutionLabel.setTextColor(Color.rgb(35, 35, 35));
        resolutionLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(resolutionLabel);

        resolutionValue = new TextView(this);
        resolutionValue.setTextSize(18);
        resolutionValue.setTextColor(Color.rgb(35, 35, 35));
        root.addView(resolutionValue, matchWrap());

        resolutionSeekBar = new SeekBar(this);
        resolutionSeekBar.setMax(OUTPUT_SCALE_STEPS);
        resolutionSeekBar.setProgress(0);
        resolutionSeekBar.setContentDescription("Output size from 100 to 200 percent");
        resolutionSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                selectedOutputScale = 1f + progressValue * OUTPUT_SCALE_STEP;
                updateProcessButtonLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(resolutionSeekBar, matchWrap());
        updateProcessButtonLabel();

        TextView resolutionHint = new TextView(this);
        resolutionHint.setText("Choose any size from 100% to 200% in 5% steps. 100% performs the strongest native RAW merge; larger sizes preserve its detail in a larger output.");
        resolutionHint.setTextSize(13);
        resolutionHint.setTextColor(Color.DKGRAY);
        resolutionHint.setPadding(0, dp(4), 0, dp(10));
        root.addView(resolutionHint);

        TextView formatLabel = new TextView(this);
        formatLabel.setText("Output format");
        formatLabel.setTextSize(16);
        formatLabel.setTextColor(Color.rgb(35, 35, 35));
        formatLabel.setPadding(0, dp(6), 0, dp(4));
        root.addView(formatLabel);

        formatSpinner = new Spinner(this);
        String[] formatLabels = new String[OutputFormat.values().length];
        for (int i = 0; i < formatLabels.length; i++) formatLabels[i] = OutputFormat.values()[i].label;
        ArrayAdapter<String> formatAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formatLabels);
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(formatAdapter);
        formatSpinner.setSelection(0);
        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedOutputFormat = OutputFormat.values()[position];
                updateSaveButtonLabel();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(formatSpinner, matchWrap());

        TextView formatHint = new TextView(this);
        formatHint.setText("DNG is lossless 16-bit LinearRaw, TIFF is lossless RGB, and JPEG is a smaller high-quality file.");
        formatHint.setTextSize(13);
        formatHint.setTextColor(Color.DKGRAY);
        formatHint.setPadding(0, dp(4), 0, dp(10));
        root.addView(formatHint);

        processButton = new Button(this);
        processButton.setText("Merge at " + scaleLabel(selectedOutputScale));
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

    private void updateProcessButtonLabel() {
        String label = scaleLabel(selectedOutputScale);
        if (resolutionValue != null) resolutionValue.setText("Output size: " + label);
        if (processButton != null) processButton.setText("Merge at " + label);
    }

    private String scaleLabel(float scale) {
        int percent = Math.round(scale * 100f);
        String multiplier = percent % 100 == 0
                ? Integer.toString(percent / 100)
                : percent % 10 == 0
                    ? String.format(java.util.Locale.US, "%.1f", percent / 100f)
                    : String.format(java.util.Locale.US, "%.2f", percent / 100f);
        return percent + "% (" + multiplier + "×)";
    }

    private void updateSaveButtonLabel() {
        if (saveButton != null) saveButton.setText("Save " + selectedOutputFormat.shortLabel);
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
            clearResult();
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
        final float outputScale = selectedOutputScale;
        clearResult();
        previewImage.setImageDrawable(null);
        processButton.setEnabled(false);
        selectButton.setEnabled(false);
        resolutionSeekBar.setEnabled(false);
        formatSpinner.setEnabled(false);
        cancelButton.setEnabled(true);
        saveButton.setEnabled(false);
        progress.setProgress(0);
        status.setText("Analyzing reference frame…");
        cancelRequested = false;

        executor.execute(() -> {
            List<FrameInfo> info = new ArrayList<>();
            Bitmap refFull = null;
            Bitmap out = null;
            try {
                for (int i = 0; i < snapshot.size(); i++) {
                    checkCancelled();
                    Bitmap preview = decodePreview(snapshot.get(i));
                    if (preview == null) throw new IllegalStateException("Could not decode " + displayName(snapshot.get(i)));
                    double sharpness = sharpness(preview);
                    double clippedFraction = clippedFraction(preview);
                    info.add(new FrameInfo(snapshot.get(i), preview, sharpness, clippedFraction));
                    updateProgress((int)(20.0 * (i + 1) / snapshot.size()), "Analyzing frame " + (i + 1) + "/" + snapshot.size());
                }

                FrameInfo ref = Collections.max(info, Comparator.comparingDouble(this::referenceScore));
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
                        f.motionField = MotionField.identity(ref.preview.getWidth(), ref.preview.getHeight());
                    } else {
                        double[] shift = estimateShift(ref.preview, f.preview, f.exposureScale);
                        f.motionField = estimateMotionField(ref.preview, f.preview,
                                shift[0], shift[1], f.exposureScale);
                        float[] centerShift = new float[2];
                        f.motionField.shiftAt(ref.preview.getWidth() * 0.5f,
                                ref.preview.getHeight() * 0.5f, centerShift);
                        f.dxPreview = centerShift[0];
                        f.dyPreview = centerShift[1];
                        f.alignmentCost = f.motionField.medianCost;
                        f.ghostFraction = ghostFraction(ref.preview, f.preview, f.motionField, f.exposureScale);
                    }
                    updateProgress(20 + (int)(30.0 * (i + 1) / info.size()), "Aligned " + (i + 1) + "/" + info.size());
                }

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
                ordered.subList(1, ordered.size()).sort(Comparator.comparingDouble(
                        (FrameInfo f) -> qualityWeight(f, ref)).reversed());
                if (ordered.size() == 1 && info.size() > 1) {
                    FrameInfo best = null;
                    for (FrameInfo candidate : info) {
                        if (candidate != ref && (best == null || candidate.alignmentCost < best.alignmentCost)) {
                            best = candidate;
                        }
                    }
                    if (best != null) ordered.add(best);
                }
                final int rejectedCount = info.size() - ordered.size();
                runOnUiThread(() -> status.setText("Stacking " + ordered.size() + " frames" +
                        (rejectedCount == 0 ? "" : " • rejected " + rejectedCount + " poor matches")));

                out = tryRawCfaFusion(ordered, ref, outputScale);
                if (out != null) {
                    publishCompleted(out, outputScale, ordered.size(), "RAW CFA");
                    return;
                }
                runOnUiThread(() -> status.setText("Using rendered-RGB compatibility fusion…"));

                refFull = decodeFull(ref.uri);
                if (refFull == null) throw new IllegalStateException("Reference DNG could not be decoded at full resolution.");
                int w = refFull.getWidth();
                int h = refFull.getHeight();
                int outputWidth = Math.round(w * outputScale);
                int outputHeight = Math.round(h * outputScale);
                validateOutputSize(w, h, outputWidth, outputHeight);
                try {
                    out = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
                } catch (OutOfMemoryError e) {
                    throw new IllegalStateException("Not enough memory for the " + outputWidth + "×" + outputHeight
                            + " output. Choose a lower output resolution.", e);
                }
                Canvas canvas = new Canvas(out);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

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

                    double qualityWeight = qualityWeight(f, ref);
                    double alpha = i == 0 ? 1.0 : qualityWeight / (totalWeight + qualityWeight);
                    paint.setAlpha(Math.max(1, Math.min(255, Math.round((float)(alpha * 255.0)))));
                    paint.setColorFilter(colorFilterFor(f.exposureScale));
                    canvas.save();
                    canvas.scale(outputScale, outputScale);
                    if (i == 0) {
                        canvas.drawBitmap(full, 0f, 0f, paint);
                    } else {
                        drawLocallyAlignedTiles(canvas, full, refFull, f.motionField,
                                f.exposureScale, paint);
                    }
                    canvas.restore();
                    paint.setColorFilter(null);
                    totalWeight += qualityWeight;

                    if (full != refFull) full.recycle();
                    updateProgress(50 + (int)(48.0 * (i + 1) / ordered.size()), "Stacking " + (i + 1) + "/" + ordered.size());
                }

                publishCompleted(out, outputScale, ordered.size(), "RGB fallback");
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    String errorMessage = friendlyError(t);
                    status.setText(t instanceof ProcessingCancelledException ? "Processing cancelled" : "Processing failed: " + errorMessage);
                    processButton.setEnabled(true);
                    selectButton.setEnabled(true);
                    resolutionSeekBar.setEnabled(true);
                    formatSpinner.setEnabled(true);
                    cancelButton.setEnabled(false);
                    saveButton.setEnabled(false);
                    if (!(t instanceof ProcessingCancelledException)) {
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
            } finally {
                for (FrameInfo frame : info) {
                    if (frame.preview != null && !frame.preview.isRecycled()) frame.preview.recycle();
                }
                if (refFull != null && !refFull.isRecycled()) refFull.recycle();
                if (out != null && out != resultBitmap && !out.isRecycled()) out.recycle();
            }
        });
    }

    private String friendlyError(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return "The device ran out of image memory. Restart the app, select fewer frames, and use 1× output.";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private Bitmap decodePreview(Uri uri) throws Exception {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                float scale = Math.min(1f, PREVIEW_MAX / (float)Math.max(w, h));
                decoder.setTargetSize(Math.max(32, Math.round(w * scale)), Math.max(32, Math.round(h * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
            });
        } catch (Exception imageDecoderFailure) {
            return DngDecoder.decode(getApplicationContext(), uri, PREVIEW_MAX);
        }
    }

    private Bitmap decodeFull(Uri uri) throws Exception {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
            });
        } catch (Exception imageDecoderFailure) {
            return DngDecoder.decode(getApplicationContext(), uri, 0);
        }
    }

    /**
     * Attempts a true sensor-domain merge. Inputs which are not compatible uncompressed Bayer
     * DNGs return null so the established rendered-RGB path can still handle them.
     */
    private Bitmap tryRawCfaFusion(List<FrameInfo> ordered, FrameInfo reference, float outputScale)
            throws Exception {
        ArrayList<DngDecoder.RawFrame> rawFrames = new ArrayList<>(ordered.size());
        try {
            updateProgress(50, "Opening original Bayer samples…");
            try {
                for (FrameInfo frame : ordered) {
                    checkCancelled();
                    rawFrames.add(DngDecoder.openRaw(getApplicationContext(), frame.uri));
                }
            } catch (IOException unsupportedRaw) {
                return null;
            }

            DngDecoder.RawFrame referenceRaw = rawFrames.get(0);
            int width = referenceRaw.width;
            int height = referenceRaw.height;
            for (DngDecoder.RawFrame raw : rawFrames) {
                if (raw.width != width || raw.height != height || raw.cfaWidth != 2
                        || raw.cfaHeight != 2) return null;
            }
            validateRawOutputSize(width, height, Math.round(width * outputScale),
                    Math.round(height * outputScale));

            double referenceMedian = rawMedian(referenceRaw);
            for (int i = 0; i < ordered.size(); i++) {
                FrameInfo frame = ordered.get(i);
                frame.rawExposureScale = i == 0 ? 1.0 : Math.max(0.5, Math.min(2.0,
                        referenceMedian / Math.max(0.0001, rawMedian(rawFrames.get(i)))));
                frame.rawConfidence = buildRawConfidence(frame, reference);
            }

            final int pixelCount;
            try {
                pixelCount = Math.multiplyExact(width, height);
            } catch (ArithmeticException tooLarge) {
                throw new IllegalStateException("The RAW image dimensions are too large.", tooLarge);
            }
            short[] merged;
            try {
                merged = new short[pixelCount];
            } catch (OutOfMemoryError memoryError) {
                throw new IllegalStateException("Not enough memory for the Bayer merge. Choose fewer frames or restart the app.",
                        memoryError);
            }

            int stripeCapacity = Math.multiplyExact(width, RAW_MERGE_STRIPE_ROWS);
            float[] sums = new float[stripeCapacity];
            float[] weights = new float[stripeCapacity];
            float[] referenceSamples = new float[stripeCapacity];
            float[] candidatePoint = new float[2];
            for (int top = 0; top < height; top += RAW_MERGE_STRIPE_ROWS) {
                checkCancelled();
                int stripeHeight = Math.min(RAW_MERGE_STRIPE_ROWS, height - top);
                int stripePixels = width * stripeHeight;
                java.util.Arrays.fill(sums, 0, stripePixels, 0f);
                java.util.Arrays.fill(weights, 0, stripePixels, 0f);
                java.util.Arrays.fill(referenceSamples, 0, stripePixels, 0f);

                for (int frameIndex = 0; frameIndex < ordered.size(); frameIndex++) {
                    checkCancelled();
                    FrameInfo frame = ordered.get(frameIndex);
                    DngDecoder.RawFrame raw = rawFrames.get(frameIndex);
                    float fullScaleY = height / Math.max(1f, frame.motionField.previewHeight);
                    int firstRow = Math.max(0, (int)Math.floor(top
                            + frame.motionField.minimumShiftY() * fullScaleY) - 4);
                    int lastRow = Math.min(height - 1, (int)Math.ceil(top + stripeHeight - 1
                            + frame.motionField.maximumShiftY() * fullScaleY) + 4);
                    if (lastRow < firstRow) continue;
                    short[] sourceRows = raw.readRows(firstRow, lastRow - firstRow + 1);
                    float globalWeight = (float)qualityWeight(frame, reference);

                    for (int localY = 0; localY < stripeHeight; localY++) {
                        int referenceY = top + localY;
                        int confidenceWidth = reference.preview.getWidth();
                        int confidenceHeight = reference.preview.getHeight();
                        int previewY = Math.min(confidenceHeight - 1,
                                referenceY * confidenceHeight / Math.max(1, height));
                        for (int referenceX = 0; referenceX < width; referenceX++) {
                            int outputIndex = localY * width + referenceX;
                            int previewX = Math.min(confidenceWidth - 1,
                                    referenceX * confidenceWidth / Math.max(1, width));
                            float confidence = (frame.rawConfidence[previewY * confidenceWidth
                                    + previewX] & 255) / 255f;
                            if (confidence <= 0f) continue;

                            frame.motionField.mapReferenceToCandidate(referenceX, referenceY,
                                    width, height, candidatePoint);
                            if (candidatePoint[0] < -1f || candidatePoint[0] > width
                                    || candidatePoint[1] < -1f || candidatePoint[1] > height) continue;
                            int phaseX = Math.floorMod(referenceX, referenceRaw.cfaWidth);
                            int phaseY = Math.floorMod(referenceY, referenceRaw.cfaHeight);
                            int wantedColor = referenceRaw.cfaColor(referenceX, referenceY);
                            int phase = matchingCfaPhase(raw, wantedColor, phaseX, phaseY);
                            float sample = sampleRawPhase(raw, sourceRows, firstRow,
                                    candidatePoint[0], candidatePoint[1], phase & 0xffff, phase >>> 16);
                            if (!Float.isFinite(sample)) continue;
                            if (frameIndex > 0 && sample >= 0.995f) continue;
                            sample = Math.max(0f, Math.min(1f,
                                    sample * (float)frame.rawExposureScale));
                            if (frameIndex == 0) referenceSamples[outputIndex] = sample;
                            float rawWeight = frameIndex == 0 ? 1f
                                    : rawRobustWeight(Math.abs(sample - referenceSamples[outputIndex]),
                                            referenceSamples[outputIndex]);
                            float weight = globalWeight * confidence * rawWeight;
                            if (weight <= 0f) continue;
                            sums[outputIndex] += sample * weight;
                            weights[outputIndex] += weight;
                        }
                    }
                }

                for (int i = 0; i < stripePixels; i++) {
                    float value = weights[i] > 0f ? sums[i] / weights[i] : 0f;
                    merged[top * width + i] = (short)Math.max(0, Math.min(65535,
                            Math.round(value * 65535f)));
                }
                int progressValue = 54 + (int)(28.0 * (top + stripeHeight) / height);
                updateProgress(progressValue, "Fusing Bayer rows " + (top + stripeHeight)
                        + "/" + height + "…");
            }

            return renderMergedRaw(referenceRaw, merged, outputScale);
        } finally {
            for (DngDecoder.RawFrame raw : rawFrames) {
                try { raw.close(); } catch (IOException ignored) { }
            }
        }
    }

    private double rawMedian(DngDecoder.RawFrame raw) throws IOException, ProcessingCancelledException {
        float[] samples = new float[1024];
        int count = 0;
        for (int sampleRow = 0; sampleRow < 32 && count < samples.length; sampleRow++) {
            checkCancelled();
            int y = Math.min(raw.height - 1,
                    Math.round((sampleRow + 0.5f) * raw.height / 32f));
            short[] row = raw.readRows(y, 1);
            for (int sampleColumn = 0; sampleColumn < 32 && count < samples.length; sampleColumn++) {
                int x = Math.min(raw.width - 1,
                        Math.round((sampleColumn + 0.5f) * raw.width / 32f));
                samples[count++] = raw.normalized(row[x] & 0xffff, x, y);
            }
        }
        java.util.Arrays.sort(samples, 0, count);
        return count == 0 ? 0.1 : samples[count / 2];
    }

    private byte[] buildRawConfidence(FrameInfo frame, FrameInfo reference) {
        int width = reference.preview.getWidth();
        int height = reference.preview.getHeight();
        byte[] confidence = new byte[width * height];
        if (frame == reference) {
            java.util.Arrays.fill(confidence, (byte)255);
            return confidence;
        }

        Bitmap candidate = frame.preview;
        if (candidate.getWidth() != width || candidate.getHeight() != height) {
            candidate = Bitmap.createScaledBitmap(candidate, width, height, true);
        }
        int[] referencePixels = new int[width * height];
        int[] candidatePixels = new int[width * height];
        reference.preview.getPixels(referencePixels, 0, width, 0, 0, width, height);
        candidate.getPixels(candidatePixels, 0, width, 0, 0, width, height);
        float[] mapped = new float[2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                frame.motionField.mapReferenceToCandidate(x, y, width, height, mapped);
                int candidateX = Math.round(mapped[0]);
                int candidateY = Math.round(mapped[1]);
                if (candidateX < 0 || candidateX >= width || candidateY < 0 || candidateY >= height) {
                    confidence[y * width + x] = 0;
                    continue;
                }
                int referenceColor = referencePixels[y * width + x];
                int candidateColor = candidatePixels[candidateY * width + candidateX];
                int referenceLuma = luma(referenceColor);
                int lumaResidual = Math.abs(scaledLuma(candidateColor, frame.exposureScale)
                        - referenceLuma);
                int colorResidual = Math.max(
                        Math.abs(scaleChannel(Color.red(candidateColor), frame.exposureScale)
                                - Color.red(referenceColor)),
                        Math.max(Math.abs(scaleChannel(Color.green(candidateColor), frame.exposureScale)
                                        - Color.green(referenceColor)),
                                Math.abs(scaleChannel(Color.blue(candidateColor), frame.exposureScale)
                                        - Color.blue(referenceColor))));
                int localWeight = robustPixelWeight(Math.max(lumaResidual, colorResidual / 2),
                        PIXEL_GHOST_THRESHOLD + referenceLuma / 16);
                boolean candidateClipped = Math.max(Color.red(candidateColor),
                        Math.max(Color.green(candidateColor), Color.blue(candidateColor))) >= 252;
                boolean referenceClipped = Math.max(Color.red(referenceColor),
                        Math.max(Color.green(referenceColor), Color.blue(referenceColor))) >= 252;
                if (candidateClipped && !referenceClipped) localWeight = 0;
                confidence[y * width + x] = (byte)localWeight;
            }
        }
        if (candidate != frame.preview) candidate.recycle();
        return confidence;
    }

    private int matchingCfaPhase(DngDecoder.RawFrame raw, int wantedColor,
                                 int preferredX, int preferredY) {
        if (raw.cfaColor(preferredX, preferredY) == wantedColor) {
            return preferredX | (preferredY << 16);
        }
        for (int y = 0; y < raw.cfaHeight; y++) {
            for (int x = 0; x < raw.cfaWidth; x++) {
                if (raw.cfaColor(x, y) == wantedColor) return x | (y << 16);
            }
        }
        return preferredX | (preferredY << 16);
    }

    private float sampleRawPhase(DngDecoder.RawFrame raw, short[] rows, int firstRow,
                                 float x, float y, int phaseX, int phaseY) {
        float latticeX = (x - phaseX) / raw.cfaWidth;
        float latticeY = (y - phaseY) / raw.cfaHeight;
        int maxLatticeX = Math.max(0, (raw.width - 1 - phaseX) / raw.cfaWidth);
        int maxLatticeY = Math.max(0, (raw.height - 1 - phaseY) / raw.cfaHeight);
        int x0Index = Math.max(0, Math.min(maxLatticeX, (int)Math.floor(latticeX)));
        int y0Index = Math.max(0, Math.min(maxLatticeY, (int)Math.floor(latticeY)));
        int x1Index = Math.min(maxLatticeX, x0Index + 1);
        int y1Index = Math.min(maxLatticeY, y0Index + 1);
        float fx = Math.max(0f, Math.min(1f, latticeX - x0Index));
        float fy = Math.max(0f, Math.min(1f, latticeY - y0Index));
        int x0 = phaseX + x0Index * raw.cfaWidth;
        int x1 = phaseX + x1Index * raw.cfaWidth;
        int y0 = phaseY + y0Index * raw.cfaHeight;
        int y1 = phaseY + y1Index * raw.cfaHeight;
        if (y0 < firstRow || y1 >= firstRow + rows.length / raw.width) return Float.NaN;
        float s00 = raw.normalized(rows[(y0 - firstRow) * raw.width + x0] & 0xffff, x0, y0);
        float s10 = raw.normalized(rows[(y0 - firstRow) * raw.width + x1] & 0xffff, x1, y0);
        float s01 = raw.normalized(rows[(y1 - firstRow) * raw.width + x0] & 0xffff, x0, y1);
        float s11 = raw.normalized(rows[(y1 - firstRow) * raw.width + x1] & 0xffff, x1, y1);
        float top = s00 + (s10 - s00) * fx;
        float bottom = s01 + (s11 - s01) * fx;
        return top + (bottom - top) * fy;
    }

    private float rawRobustWeight(float residual, float referenceValue) {
        float threshold = 0.025f + 0.08f * (float)Math.sqrt(Math.max(0f, referenceValue));
        if (residual <= threshold) return 1f;
        float normalized = (residual - threshold) / threshold;
        if (normalized >= 1f) return 0f;
        float shoulder = 1f - normalized * normalized;
        return shoulder * shoulder;
    }

    private Bitmap renderMergedRaw(DngDecoder.RawFrame raw, short[] merged, float outputScale)
            throws ProcessingCancelledException {
        updateProgress(83, "Demosaicing merged Bayer image once…");
        Bitmap nativeOutput;
        try {
            nativeOutput = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError memoryError) {
            throw new IllegalStateException("Not enough memory to render the merged RAW image.", memoryError);
        }
        int[] outputRow = new int[raw.width];
        float[] rgb = new float[3];
        for (int y = 0; y < raw.height; y++) {
            if ((y & 31) == 0) {
                checkCancelled();
                updateProgress(83 + (int)(14.0 * y / raw.height),
                        "Developing merged RAW " + y + "/" + raw.height + "…");
            }
            for (int x = 0; x < raw.width; x++) {
                demosaicPixel(raw, merged, x, y, rgb);
                outputRow[x] = raw.renderSrgb(rgb[0], rgb[1], rgb[2]);
            }
            nativeOutput.setPixels(outputRow, 0, raw.width, 0, y, raw.width, 1);
        }

        int outputWidth = Math.max(1, Math.round(raw.width * outputScale));
        int outputHeight = Math.max(1, Math.round(raw.height * outputScale));
        if (outputWidth == raw.width && outputHeight == raw.height) return nativeOutput;
        updateProgress(98, "Resampling final output…");
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(nativeOutput, outputWidth, outputHeight, true);
            nativeOutput.recycle();
            return scaled;
        } catch (OutOfMemoryError memoryError) {
            nativeOutput.recycle();
            throw new IllegalStateException("Not enough memory for the selected output size. Choose a lower resolution.",
                    memoryError);
        }
    }

    /** Lightweight edge-directed Bayer demosaic; all color development occurs after fusion. */
    private void demosaicPixel(DngDecoder.RawFrame raw, short[] mosaic, int x, int y,
                               float[] output) {
        int centerColor = raw.cfaColor(x, y);
        float center = mergedValue(mosaic, raw.width, raw.height, x, y);
        float red;
        float green;
        float blue;
        if (centerColor == 0 || centerColor == 2) {
            float left = mergedValue(mosaic, raw.width, raw.height, x - 1, y);
            float right = mergedValue(mosaic, raw.width, raw.height, x + 1, y);
            float up = mergedValue(mosaic, raw.width, raw.height, x, y - 1);
            float down = mergedValue(mosaic, raw.width, raw.height, x, y + 1);
            float sameLeft = mergedValue(mosaic, raw.width, raw.height, x - 2, y);
            float sameRight = mergedValue(mosaic, raw.width, raw.height, x + 2, y);
            float sameUp = mergedValue(mosaic, raw.width, raw.height, x, y - 2);
            float sameDown = mergedValue(mosaic, raw.width, raw.height, x, y + 2);
            float horizontal = 0.5f * (left + right) + 0.25f * (2f * center - sameLeft - sameRight);
            float vertical = 0.5f * (up + down) + 0.25f * (2f * center - sameUp - sameDown);
            float horizontalGradient = Math.abs(left - right) + Math.abs(2f * center - sameLeft - sameRight);
            float verticalGradient = Math.abs(up - down) + Math.abs(2f * center - sameUp - sameDown);
            green = clamp01(horizontalGradient < verticalGradient ? horizontal
                    : verticalGradient < horizontalGradient ? vertical : 0.5f * (horizontal + vertical));
            float diagonalA = 0.5f * (mergedValue(mosaic, raw.width, raw.height, x - 1, y - 1)
                    + mergedValue(mosaic, raw.width, raw.height, x + 1, y + 1));
            float diagonalB = 0.5f * (mergedValue(mosaic, raw.width, raw.height, x + 1, y - 1)
                    + mergedValue(mosaic, raw.width, raw.height, x - 1, y + 1));
            float gradientA = Math.abs(mergedValue(mosaic, raw.width, raw.height, x - 1, y - 1)
                    - mergedValue(mosaic, raw.width, raw.height, x + 1, y + 1));
            float gradientB = Math.abs(mergedValue(mosaic, raw.width, raw.height, x + 1, y - 1)
                    - mergedValue(mosaic, raw.width, raw.height, x - 1, y + 1));
            float opposite = gradientA < gradientB ? diagonalA
                    : gradientB < gradientA ? diagonalB : 0.5f * (diagonalA + diagonalB);
            if (centerColor == 0) {
                red = center;
                blue = opposite;
            } else {
                red = opposite;
                blue = center;
            }
        } else {
            green = center;
            boolean redHorizontal = raw.cfaColor(x - 1, y) == 0 || raw.cfaColor(x + 1, y) == 0;
            float horizontal = 0.5f * (mergedValue(mosaic, raw.width, raw.height, x - 1, y)
                    + mergedValue(mosaic, raw.width, raw.height, x + 1, y));
            float vertical = 0.5f * (mergedValue(mosaic, raw.width, raw.height, x, y - 1)
                    + mergedValue(mosaic, raw.width, raw.height, x, y + 1));
            red = redHorizontal ? horizontal : vertical;
            blue = redHorizontal ? vertical : horizontal;
        }
        output[0] = clamp01(red);
        output[1] = clamp01(green);
        output[2] = clamp01(blue);
    }

    private float mergedValue(short[] mosaic, int width, int height, int x, int y) {
        int safeX = Math.max(0, Math.min(width - 1, x));
        int safeY = Math.max(0, Math.min(height - 1, y));
        return (mosaic[safeY * width + safeX] & 0xffff) / 65535f;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void validateRawOutputSize(int inputWidth, int inputHeight,
                                       int outputWidth, int outputHeight) {
        long inputPixels = (long)inputWidth * inputHeight;
        long outputPixels = (long)outputWidth * outputHeight;
        if (outputPixels <= 0 || outputPixels > MAX_OUTPUT_PIXELS) {
            throw new IllegalStateException("The selected mode would create " + outputWidth + "×" + outputHeight
                    + ". Choose a lower resolution; the safety limit is 70 MP.");
        }
        long previewBytes = (long)PREVIEW_MAX * PREVIEW_MAX * 4L * Math.min(frames.size(), MAX_FRAMES);
        long nativeBitmapBytes = inputPixels * 4L;
        long scaledBitmapBytes = outputPixels == inputPixels ? 0L : outputPixels * 4L;
        long rawMergeBytes = inputPixels * 2L;
        long stripeBytes = (long)inputWidth * RAW_MERGE_STRIPE_ROWS * 16L;
        long estimatedBytes = nativeBitmapBytes + scaledBitmapBytes + rawMergeBytes + stripeBytes
                + previewBytes + 24L * 1024L * 1024L;
        long safeBudget = Runtime.getRuntime().maxMemory() * 85L / 100L;
        if (estimatedBytes > safeBudget) {
            throw new IllegalStateException("RAW fusion at this size needs about "
                    + (estimatedBytes / (1024L * 1024L)) + " MB while this device allows about "
                    + (safeBudget / (1024L * 1024L)) + " MB safely. Choose a lower output resolution.");
        }
    }

    private void publishCompleted(Bitmap completed, float outputScale, int frameCount, String engine) {
        resultBitmap = completed;
        resultOutputScale = outputScale;
        Bitmap display = makeDisplayPreview(completed);
        resultPreview = display;
        runOnUiThread(() -> {
            progress.setProgress(100);
            status.setText("Done • " + completed.getWidth() + "×" + completed.getHeight()
                    + " • " + frameCount + " frames • " + engine);
            previewImage.setImageBitmap(display);
            processButton.setEnabled(true);
            selectButton.setEnabled(true);
            resolutionSeekBar.setEnabled(true);
            formatSpinner.setEnabled(true);
            cancelButton.setEnabled(false);
            saveButton.setEnabled(true);
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

    private double[] estimateShift(Bitmap ref, Bitmap img, double exposureScale) {
        Bitmap candidate = img;
        if (img.getWidth() != ref.getWidth() || img.getHeight() != ref.getHeight()) {
            candidate = Bitmap.createScaledBitmap(img, ref.getWidth(), ref.getHeight(), true);
        }
        int bestX = 0, bestY = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy += 2) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += 2) {
                double s = shiftCost(ref, candidate, dx, dy, 4, exposureScale);
                if (s < best) { best = s; bestX = dx; bestY = dy; }
            }
        }
        int coarseX = bestX, coarseY = bestY;
        best = Double.POSITIVE_INFINITY;
        for (int dy = coarseY - 2; dy <= coarseY + 2; dy++) {
            for (int dx = coarseX - 2; dx <= coarseX + 2; dx++) {
                double s = shiftCost(ref, candidate, dx, dy, 2, exposureScale);
                if (s < best) { best = s; bestX = dx; bestY = dy; }
            }
        }
        double cxm = shiftCost(ref, candidate, bestX - 1, bestY, 2, exposureScale);
        double cx0 = shiftCost(ref, candidate, bestX, bestY, 2, exposureScale);
        double cxp = shiftCost(ref, candidate, bestX + 1, bestY, 2, exposureScale);
        double cym = shiftCost(ref, candidate, bestX, bestY - 1, 2, exposureScale);
        double cy0 = cx0;
        double cyp = shiftCost(ref, candidate, bestX, bestY + 1, 2, exposureScale);
        double subX = parabolicOffset(cxm, cx0, cxp);
        double subY = parabolicOffset(cym, cy0, cyp);
        if (candidate != img) candidate.recycle();
        return new double[]{bestX + subX, bestY + subY, best};
    }

    private MotionField estimateMotionField(Bitmap reference, Bitmap image, double globalDx,
                                             double globalDy, double exposureScale)
            throws ProcessingCancelledException {
        Bitmap candidate = image;
        if (image.getWidth() != reference.getWidth() || image.getHeight() != reference.getHeight()) {
            candidate = Bitmap.createScaledBitmap(image, reference.getWidth(), reference.getHeight(), true);
        }
        int width = reference.getWidth();
        int height = reference.getHeight();
        int patchRadius = Math.max(18, Math.min(42, Math.min(width, height) / 9));
        int margin = patchRadius + LOCAL_SEARCH_RADIUS + 3;
        if (width <= margin * 2 + 8 || height <= margin * 2 + 8) {
            if (candidate != image) candidate.recycle();
            return MotionField.translation(width, height, (float)globalDx, (float)globalDy, 0);
        }

        float minX = margin;
        float minY = margin;
        float maxX = width - 1f - margin;
        float maxY = height - 1f - margin;
        ArrayList<MotionSample> samples = new ArrayList<>(MOTION_GRID_SIZE * MOTION_GRID_SIZE);
        double[] costs = new double[MOTION_GRID_SIZE * MOTION_GRID_SIZE];
        int index = 0;
        for (int gy = 0; gy < MOTION_GRID_SIZE; gy++) {
            checkCancelled();
            float y = minY + (maxY - minY) * gy / (MOTION_GRID_SIZE - 1f);
            for (int gx = 0; gx < MOTION_GRID_SIZE; gx++) {
                float x = minX + (maxX - minX) * gx / (MOTION_GRID_SIZE - 1f);
                LocalShift local = estimateLocalShift(reference, candidate, Math.round(x), Math.round(y),
                        globalDx, globalDy, patchRadius, exposureScale);
                MotionSample sample = new MotionSample(x, y, width, height, local);
                samples.add(sample);
                costs[index++] = local.cost;
            }
        }

        double medianCost = medianFinite(costs, 0);
        double[] modelX = fitShiftModel(samples, true, globalDx);
        double[] modelY = fitShiftModel(samples, false, globalDy);
        double[] residuals = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            MotionSample sample = samples.get(i);
            double predictedX = modelValue(modelX, sample.u, sample.v);
            double predictedY = modelValue(modelY, sample.u, sample.v);
            residuals[i] = Math.hypot(sample.dx - predictedX, sample.dy - predictedY);
        }
        double medianResidual = medianFinite(residuals, 0);
        double robustScale = Math.max(1.25, medianResidual * 2.5);
        for (int i = 0; i < samples.size(); i++) {
            double normalized = residuals[i] / robustScale;
            samples.get(i).robustWeight = 1.0 / (1.0 + normalized * normalized * normalized * normalized);
        }
        modelX = fitShiftModel(samples, true, globalDx);
        modelY = fitShiftModel(samples, false, globalDy);

        float[] shiftsX = new float[samples.size()];
        float[] shiftsY = new float[samples.size()];
        float[] affineX = new float[samples.size()];
        float[] affineY = new float[samples.size()];
        double maximumResidual = Math.max(2.25, medianResidual * 2.75);
        double maximumCost = Math.max(medianCost + 6.0, medianCost * 2.5);
        for (int i = 0; i < samples.size(); i++) {
            MotionSample sample = samples.get(i);
            float predictedX = (float)modelValue(modelX, sample.u, sample.v);
            float predictedY = (float)modelValue(modelY, sample.u, sample.v);
            affineX[i] = predictedX;
            affineY[i] = predictedY;
            double residual = Math.hypot(sample.dx - predictedX, sample.dy - predictedY);
            boolean unreliable = sample.texture < 3.0 || sample.cost > maximumCost
                    || residual > maximumResidual || !Double.isFinite(sample.cost);
            if (unreliable) {
                shiftsX[i] = predictedX;
                shiftsY[i] = predictedY;
            } else {
                shiftsX[i] = predictedX + 0.75f * ((float)sample.dx - predictedX);
                shiftsY[i] = predictedY + 0.75f * ((float)sample.dy - predictedY);
            }
        }

        // Smooth only the local residual. The affine component remains untouched, so rotation
        // and small scale changes are preserved while isolated moving objects cannot bend the mesh.
        float[] smoothX = shiftsX.clone();
        float[] smoothY = shiftsY.clone();
        for (int gy = 0; gy < MOTION_GRID_SIZE; gy++) {
            for (int gx = 0; gx < MOTION_GRID_SIZE; gx++) {
                int center = gy * MOTION_GRID_SIZE + gx;
                float residualX = 0f;
                float residualY = 0f;
                int count = 0;
                for (int ny = Math.max(0, gy - 1); ny <= Math.min(MOTION_GRID_SIZE - 1, gy + 1); ny++) {
                    for (int nx = Math.max(0, gx - 1); nx <= Math.min(MOTION_GRID_SIZE - 1, gx + 1); nx++) {
                        int neighbor = ny * MOTION_GRID_SIZE + nx;
                        residualX += shiftsX[neighbor] - affineX[neighbor];
                        residualY += shiftsY[neighbor] - affineY[neighbor];
                        count++;
                    }
                }
                smoothX[center] = affineX[center] + residualX / Math.max(1, count);
                smoothY[center] = affineY[center] + residualY / Math.max(1, count);
            }
        }
        if (candidate != image) candidate.recycle();
        return new MotionField(width, height, minX, minY, maxX, maxY,
                smoothX, smoothY, medianCost);
    }

    private LocalShift estimateLocalShift(Bitmap reference, Bitmap candidate, int centerX, int centerY,
                                          double initialDx, double initialDy, int patchRadius,
                                          double exposureScale) {
        int initialX = (int)Math.round(initialDx);
        int initialY = (int)Math.round(initialDy);
        int bestX = initialX;
        int bestY = initialY;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int dy = initialY - LOCAL_SEARCH_RADIUS; dy <= initialY + LOCAL_SEARCH_RADIUS; dy++) {
            for (int dx = initialX - LOCAL_SEARCH_RADIUS; dx <= initialX + LOCAL_SEARCH_RADIUS; dx++) {
                double cost = patchShiftCost(reference, candidate, centerX, centerY,
                        dx, dy, patchRadius, 2, exposureScale);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        double centerCost = patchShiftCost(reference, candidate, centerX, centerY,
                bestX, bestY, patchRadius, 2, exposureScale);
        double subX = parabolicOffset(
                patchShiftCost(reference, candidate, centerX, centerY,
                        bestX - 1, bestY, patchRadius, 2, exposureScale),
                centerCost,
                patchShiftCost(reference, candidate, centerX, centerY,
                        bestX + 1, bestY, patchRadius, 2, exposureScale));
        double subY = parabolicOffset(
                patchShiftCost(reference, candidate, centerX, centerY,
                        bestX, bestY - 1, patchRadius, 2, exposureScale),
                centerCost,
                patchShiftCost(reference, candidate, centerX, centerY,
                        bestX, bestY + 1, patchRadius, 2, exposureScale));
        double texture = patchTexture(reference, centerX, centerY, patchRadius, 3);
        return new LocalShift(bestX + subX, bestY + subY, bestCost, texture);
    }

    private double patchShiftCost(Bitmap reference, Bitmap candidate, int centerX, int centerY,
                                  int dx, int dy, int radius, int step, double exposureScale) {
        long total = 0;
        int count = 0;
        int minY = Math.max(2, centerY - radius);
        int maxY = Math.min(reference.getHeight() - 2, centerY + radius);
        int minX = Math.max(2, centerX - radius);
        int maxX = Math.min(reference.getWidth() - 2, centerX + radius);
        for (int y = minY; y <= maxY; y += step) {
            int candidateY = y + dy;
            if (candidateY < 2 || candidateY >= candidate.getHeight() - 1) continue;
            for (int x = minX; x <= maxX; x += step) {
                int candidateX = x + dx;
                if (candidateX < 2 || candidateX >= candidate.getWidth() - 1) continue;
                int referenceCenter = luma(reference.getPixel(x, y));
                int candidateCenter = scaledLuma(candidate.getPixel(candidateX, candidateY), exposureScale);
                int referenceHorizontal = referenceCenter - luma(reference.getPixel(x - 1, y));
                int candidateHorizontal = candidateCenter
                        - scaledLuma(candidate.getPixel(candidateX - 1, candidateY), exposureScale);
                int referenceVertical = referenceCenter - luma(reference.getPixel(x, y - 1));
                int candidateVertical = candidateCenter
                        - scaledLuma(candidate.getPixel(candidateX, candidateY - 1), exposureScale);
                total += Math.abs(referenceHorizontal - candidateHorizontal)
                        + Math.abs(referenceVertical - candidateVertical);
                count++;
            }
        }
        return count < 32 ? Double.POSITIVE_INFINITY : total / (double)count;
    }

    private double patchTexture(Bitmap bitmap, int centerX, int centerY, int radius, int step) {
        long total = 0;
        int count = 0;
        for (int y = Math.max(1, centerY - radius); y <= Math.min(bitmap.getHeight() - 2, centerY + radius); y += step) {
            for (int x = Math.max(1, centerX - radius); x <= Math.min(bitmap.getWidth() - 2, centerX + radius); x += step) {
                int center = luma(bitmap.getPixel(x, y));
                total += Math.abs(center - luma(bitmap.getPixel(x - 1, y)))
                        + Math.abs(center - luma(bitmap.getPixel(x, y - 1)));
                count++;
            }
        }
        return count == 0 ? 0 : total / (double)count;
    }

    private double[] fitShiftModel(List<MotionSample> samples, boolean horizontal, double fallback) {
        double[][] normal = new double[3][4];
        for (MotionSample sample : samples) {
            double textureWeight = Math.max(0.05, Math.min(1.0, sample.texture / 18.0));
            double costWeight = Double.isFinite(sample.cost) ? 1.0 / (1.0 + sample.cost / 12.0) : 0.0;
            double weight = textureWeight * costWeight * sample.robustWeight;
            double[] feature = {1.0, sample.u, sample.v};
            double value = horizontal ? sample.dx : sample.dy;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    normal[row][column] += weight * feature[row] * feature[column];
                }
                normal[row][3] += weight * feature[row] * value;
            }
        }
        double[] solved = solve3x3(normal);
        if (solved == null) return new double[]{fallback, 0, 0};
        double maximumSlopeX = 0.06 * PREVIEW_MAX;
        solved[1] = Math.max(-maximumSlopeX, Math.min(maximumSlopeX, solved[1]));
        solved[2] = Math.max(-maximumSlopeX, Math.min(maximumSlopeX, solved[2]));
        return solved;
    }

    private double[] solve3x3(double[][] matrix) {
        double[][] values = new double[3][4];
        for (int row = 0; row < 3; row++) System.arraycopy(matrix[row], 0, values[row], 0, 4);
        for (int column = 0; column < 3; column++) {
            int pivot = column;
            for (int row = column + 1; row < 3; row++) {
                if (Math.abs(values[row][column]) > Math.abs(values[pivot][column])) pivot = row;
            }
            if (Math.abs(values[pivot][column]) < 1e-8) return null;
            double[] swap = values[column];
            values[column] = values[pivot];
            values[pivot] = swap;
            double divisor = values[column][column];
            for (int item = column; item < 4; item++) values[column][item] /= divisor;
            for (int row = 0; row < 3; row++) {
                if (row == column) continue;
                double factor = values[row][column];
                for (int item = column; item < 4; item++) values[row][item] -= factor * values[column][item];
            }
        }
        return new double[]{values[0][3], values[1][3], values[2][3]};
    }

    private double modelValue(double[] model, double u, double v) {
        return model[0] + model[1] * u + model[2] * v;
    }

    private double medianFinite(double[] values, double fallback) {
        double[] finite = new double[values.length];
        int count = 0;
        for (double value : values) if (Double.isFinite(value)) finite[count++] = value;
        if (count == 0) return fallback;
        java.util.Arrays.sort(finite, 0, count);
        int middle = count / 2;
        return count % 2 == 0 ? (finite[middle - 1] + finite[middle]) * 0.5 : finite[middle];
    }

    private double parabolicOffset(double left, double center, double right) {
        double denom = left - 2.0 * center + right;
        if (Math.abs(denom) < 1e-9) return 0;
        double v = 0.5 * (left - right) / denom;
        return Math.max(-0.75, Math.min(0.75, v));
    }

    private double shiftCost(Bitmap a, Bitmap b, int dx, int dy, int step, double exposureScale) {
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
                int bCenter = scaledLuma(b.getPixel(bx, by), exposureScale);
                int aHorizontal = aCenter - luma(a.getPixel(x - 1, y));
                int bHorizontal = bCenter - scaledLuma(b.getPixel(bx - 1, by), exposureScale);
                int aVertical = aCenter - luma(a.getPixel(x, y - 1));
                int bVertical = bCenter - scaledLuma(b.getPixel(bx, by - 1), exposureScale);
                total += Math.abs(aHorizontal - bHorizontal);
                total += Math.abs(aVertical - bVertical);
                count++;
            }
        }
        return count == 0 ? Double.POSITIVE_INFINITY : (double)total / count;
    }

    private double ghostFraction(Bitmap reference, Bitmap image, MotionField motionField, double exposureScale) {
        Bitmap candidate = image;
        if (image.getWidth() != reference.getWidth() || image.getHeight() != reference.getHeight()) {
            candidate = Bitmap.createScaledBitmap(image, reference.getWidth(), reference.getHeight(), true);
        }
        int width = reference.getWidth();
        int height = reference.getHeight();
        int margin = SEARCH_RADIUS + 5;
        int changed = 0;
        int samples = 0;
        float[] candidatePoint = new float[2];
        for (int y = margin + 1; y < height - margin; y += 3) {
            for (int x = margin + 1; x < width - margin; x += 3) {
                motionField.mapReferenceToCandidate(x, y, width, height, candidatePoint);
                int imageX = Math.round(candidatePoint[0]);
                int imageY = Math.round(candidatePoint[1]);
                if (imageX < 1 || imageX >= width || imageY < 1 || imageY >= height) continue;
                int referenceCenter = luma(reference.getPixel(x, y));
                int imageCenter = scaledLuma(candidate.getPixel(imageX, imageY), exposureScale);
                int referenceGradient = Math.abs(referenceCenter - luma(reference.getPixel(x - 1, y)))
                        + Math.abs(referenceCenter - luma(reference.getPixel(x, y - 1)));
                int imageGradient = Math.abs(imageCenter - scaledLuma(candidate.getPixel(imageX - 1, imageY), exposureScale))
                    + Math.abs(imageCenter - scaledLuma(candidate.getPixel(imageX, imageY - 1), exposureScale));
                if (Math.abs(referenceGradient - imageGradient) > 42
                        && Math.abs(referenceCenter - imageCenter) > 28) changed++;
                samples++;
            }
        }
        if (candidate != image) candidate.recycle();
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

    private double clippedFraction(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, Math.max(width, height) / 160);
        int clipped = 0;
        int count = 0;
        for (int y = step / 2; y < height; y += step) {
            for (int x = step / 2; x < width; x += step) {
                int color = bitmap.getPixel(x, y);
                if (Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color))) >= 252) {
                    clipped++;
                }
                count++;
            }
        }
        return count == 0 ? 1.0 : clipped / (double)count;
    }

    private double referenceScore(FrameInfo frame) {
        double highlightWeight = Math.max(0.20, 1.0 - 5.0 * frame.clippedFraction);
        return frame.sharpness * highlightWeight;
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

    private double qualityWeight(FrameInfo frame, FrameInfo reference) {
        if (frame == reference) return 1.0;
        double sharpnessRatio = frame.sharpness / Math.max(reference.sharpness, 1e-9);
        double sharpnessWeight = Math.max(0.25, Math.min(1.0, sharpnessRatio));
        double alignmentWeight = Math.exp(-frame.alignmentCost / 24.0);
        double motionWeight = 1.0 - Math.min(1.0, frame.ghostFraction / MAX_GHOST_FRACTION);
        double highlightWeight = Math.max(0.25, 1.0 - 3.0 * frame.clippedFraction);
        double exposureWeight = Math.exp(-0.6 * Math.abs(Math.log(Math.max(0.01, frame.exposureScale))));
        return Math.max(0.05, Math.min(1.0,
                sharpnessWeight * Math.max(0.10, alignmentWeight) * motionWeight
                        * highlightWeight * exposureWeight));
    }

    private void validateOutputSize(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        long inputPixels = (long)inputWidth * inputHeight;
        long outputPixels = (long)outputWidth * outputHeight;
        if (outputPixels <= 0 || outputPixels > MAX_OUTPUT_PIXELS) {
            throw new IllegalStateException("The selected mode would create " + outputWidth + "×" + outputHeight
                    + " (" + (outputPixels / 1_000_000L) + " MP). Choose a lower resolution; the safety limit is 70 MP.");
        }

        long previewBytes = (long)PREVIEW_MAX * PREVIEW_MAX * 4L * Math.min(frames.size(), MAX_FRAMES);
        long estimatedBytes = outputPixels * 4L + inputPixels * 8L + previewBytes + 32L * 1024L * 1024L;
        long safeBudget = Runtime.getRuntime().maxMemory() * 85L / 100L;
        if (estimatedBytes > safeBudget) {
            throw new IllegalStateException("This mode needs about " + (estimatedBytes / (1024L * 1024L))
                    + " MB while this device allows about " + (safeBudget / (1024L * 1024L))
                    + " MB safely. Choose a lower output resolution.");
        }
    }

    private void drawLocallyAlignedTiles(Canvas canvas, Bitmap image, Bitmap reference,
                                         MotionField motionField, double exposureScale, Paint paint)
            throws ProcessingCancelledException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] imagePixels = new int[FUSION_TILE_SIZE * FUSION_TILE_SIZE];
        int[] referencePixels = new int[0];
        float[] mapped = new float[2];
        float[] vertices = new float[8];
        for (int top = 0; top < height; top += FUSION_TILE_SIZE) {
            checkCancelled();
            int tileHeight = Math.min(FUSION_TILE_SIZE, height - top);
            for (int left = 0; left < width; left += FUSION_TILE_SIZE) {
                int tileWidth = Math.min(FUSION_TILE_SIZE, width - left);
                image.getPixels(imagePixels, 0, tileWidth, left, top, tileWidth, tileHeight);

                float minReferenceX = Float.POSITIVE_INFINITY;
                float minReferenceY = Float.POSITIVE_INFINITY;
                float maxReferenceX = Float.NEGATIVE_INFINITY;
                float maxReferenceY = Float.NEGATIVE_INFINITY;
                for (int sampleY = 0; sampleY <= 2; sampleY++) {
                    for (int sampleX = 0; sampleX <= 2; sampleX++) {
                        float candidateX = left + tileWidth * sampleX * 0.5f;
                        float candidateY = top + tileHeight * sampleY * 0.5f;
                        motionField.mapCandidateToReference(candidateX, candidateY,
                                width, height, mapped);
                        minReferenceX = Math.min(minReferenceX, mapped[0]);
                        minReferenceY = Math.min(minReferenceY, mapped[1]);
                        maxReferenceX = Math.max(maxReferenceX, mapped[0]);
                        maxReferenceY = Math.max(maxReferenceY, mapped[1]);
                    }
                }
                int referenceLeft = Math.max(0, (int)Math.floor(minReferenceX) - 2);
                int referenceTop = Math.max(0, (int)Math.floor(minReferenceY) - 2);
                int referenceRight = Math.min(width - 1, (int)Math.ceil(maxReferenceX) + 2);
                int referenceBottom = Math.min(height - 1, (int)Math.ceil(maxReferenceY) + 2);
                int referenceWidth = Math.max(0, referenceRight - referenceLeft + 1);
                int referenceHeight = Math.max(0, referenceBottom - referenceTop + 1);
                int requiredReferencePixels = referenceWidth * referenceHeight;
                if (referencePixels.length < requiredReferencePixels) {
                    referencePixels = new int[requiredReferencePixels];
                }
                if (requiredReferencePixels > 0) {
                    reference.getPixels(referencePixels, 0, referenceWidth, referenceLeft, referenceTop,
                            referenceWidth, referenceHeight);
                }

                for (int row = 0; row < tileHeight; row++) {
                    int candidateY = top + row;
                    for (int column = 0; column < tileWidth; column++) {
                        int candidateX = left + column;
                        int index = row * tileWidth + column;
                        int color = imagePixels[index];
                        motionField.mapCandidateToReference(candidateX, candidateY,
                                width, height, mapped);
                        float referenceX = mapped[0];
                        float referenceY = mapped[1];
                        if (referenceWidth == 0 || referenceHeight == 0 || referenceX < 0
                                || referenceX > width - 1 || referenceY < 0 || referenceY > height - 1) {
                            imagePixels[index] = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
                            continue;
                        }
                        int referenceColor = bilinearColor(referencePixels, referenceWidth, referenceHeight,
                                referenceX - referenceLeft, referenceY - referenceTop);
                    int referenceLuma = luma(referenceColor);
                    int candidateLuma = scaledLuma(color, exposureScale);
                    int lumaResidual = Math.abs(candidateLuma - referenceLuma);
                    int colorResidual = Math.max(
                            Math.abs(scaleChannel(Color.red(color), exposureScale) - Color.red(referenceColor)),
                            Math.max(
                                    Math.abs(scaleChannel(Color.green(color), exposureScale) - Color.green(referenceColor)),
                                    Math.abs(scaleChannel(Color.blue(color), exposureScale) - Color.blue(referenceColor))));
                    int difference = Math.max(lumaResidual, colorResidual / 2);
                    int threshold = PIXEL_GHOST_THRESHOLD + referenceLuma / 16;
                    int localWeight = robustPixelWeight(difference, threshold);
                        boolean candidateClipped = Math.max(Color.red(color),
                                Math.max(Color.green(color), Color.blue(color))) >= 252;
                        boolean referenceClipped = Math.max(Color.red(referenceColor),
                                Math.max(Color.green(referenceColor), Color.blue(referenceColor))) >= 252;
                        if (candidateClipped && !referenceClipped) localWeight = 0;
                    imagePixels[index] = Color.argb(localWeight, Color.red(color), Color.green(color), Color.blue(color));
                    }
                }

                Bitmap tile = Bitmap.createBitmap(imagePixels, 0, tileWidth,
                        tileWidth, tileHeight, Bitmap.Config.ARGB_8888);
                setMeshVertex(motionField, vertices, 0, left, top, width, height, mapped);
                setMeshVertex(motionField, vertices, 2, left + tileWidth, top, width, height, mapped);
                setMeshVertex(motionField, vertices, 4, left, top + tileHeight, width, height, mapped);
                setMeshVertex(motionField, vertices, 6, left + tileWidth, top + tileHeight, width, height, mapped);
                canvas.drawBitmapMesh(tile, 1, 1, vertices, 0, null, 0, paint);
                tile.recycle();
            }
        }
    }

    private void setMeshVertex(MotionField motionField, float[] vertices, int offset,
                               float candidateX, float candidateY, int width, int height,
                               float[] mapped) {
        motionField.mapCandidateToReference(candidateX, candidateY, width, height, mapped);
        vertices[offset] = mapped[0];
        vertices[offset + 1] = mapped[1];
    }

    private int bilinearColor(int[] pixels, int width, int height, float x, float y) {
        float safeX = Math.max(0f, Math.min(width - 1f, x));
        float safeY = Math.max(0f, Math.min(height - 1f, y));
        int x0 = (int)Math.floor(safeX);
        int y0 = (int)Math.floor(safeY);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = safeX - x0;
        float fy = safeY - y0;
        int c00 = pixels[y0 * width + x0];
        int c10 = pixels[y0 * width + x1];
        int c01 = pixels[y1 * width + x0];
        int c11 = pixels[y1 * width + x1];
        int red = bilinearChannel(Color.red(c00), Color.red(c10), Color.red(c01), Color.red(c11), fx, fy);
        int green = bilinearChannel(Color.green(c00), Color.green(c10), Color.green(c01), Color.green(c11), fx, fy);
        int blue = bilinearChannel(Color.blue(c00), Color.blue(c10), Color.blue(c01), Color.blue(c11), fx, fy);
        return Color.rgb(red, green, blue);
    }

    private int bilinearChannel(int c00, int c10, int c01, int c11, float fx, float fy) {
        float top = c00 + (c10 - c00) * fx;
        float bottom = c01 + (c11 - c01) * fx;
        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * fy)));
    }

    private int robustPixelWeight(int difference, int threshold) {
        if (difference <= threshold) return 255;
        double normalized = (difference - threshold) / (double)Math.max(1, threshold * 2);
        if (normalized >= 1.0) return 0;
        double value = 1.0 - normalized * normalized;
        return (int)Math.round(255.0 * value * value);
    }

    private int scaleChannel(int value, double scale) {
        return Math.max(0, Math.min(255, (int)Math.round(value * scale)));
    }

    private int luma(int c) {
        return (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) >> 8;
    }

    private void chooseSaveLocation() {
        if (resultBitmap == null) return;
        pendingSaveFormat = selectedOutputFormat;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(pendingSaveFormat.mimeType);
        i.putExtra(Intent.EXTRA_TITLE, outputFileName(pendingSaveFormat));
        startActivityForResult(i, SAVE_RESULT);
    }

    private String outputFileName(OutputFormat format) {
        int percent = Math.round(resultOutputScale * 100f);
        return "SuperRes_" + percent + "pct." + format.extension;
    }

    private void saveResult(Uri uri) {
        if (resultBitmap == null) return;
        Bitmap bitmapToSave = resultBitmap;
        OutputFormat format = pendingSaveFormat;
        String fileName = outputFileName(format);
        selectButton.setEnabled(false);
        processButton.setEnabled(false);
        resolutionSeekBar.setEnabled(false);
        formatSpinner.setEnabled(false);
        saveButton.setEnabled(false);
        status.setText("Saving " + fileName + "…");
        executor.execute(() -> {
            try (OutputStream raw = getContentResolver().openOutputStream(uri, "w")) {
                if (raw == null) throw new IllegalStateException("Could not open output file.");
                try (BufferedOutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
                    if (format == OutputFormat.DNG) {
                        DngWriter.write(out, bitmapToSave);
                    } else if (format == OutputFormat.JPEG) {
                        if (!bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                            throw new IOException("Android could not encode the JPEG.");
                        }
                    } else {
                        writeTiff(out, bitmapToSave);
                    }
                }
                runOnUiThread(() -> {
                    status.setText("Saved " + fileName);
                    Toast.makeText(this, "Saved " + fileName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText(format.shortLabel + " save failed: " + e.getMessage());
                    Toast.makeText(this, format.shortLabel + " save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                runOnUiThread(() -> {
                    selectButton.setEnabled(true);
                    processButton.setEnabled(frames.size() >= 2);
                    resolutionSeekBar.setEnabled(true);
                    formatSpinner.setEnabled(true);
                    saveButton.setEnabled(resultBitmap != null);
                });
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

    private void clearResult() {
        Bitmap oldResult = resultBitmap;
        Bitmap oldPreview = resultPreview;
        resultBitmap = null;
        resultPreview = null;
        if (oldPreview != null && oldPreview != oldResult && !oldPreview.isRecycled()) oldPreview.recycle();
        if (oldResult != null && !oldResult.isRecycled()) oldResult.recycle();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        clearResult();
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
        if (cancelRequested || Thread.currentThread().isInterrupted()) throw new ProcessingCancelledException();
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
        final double clippedFraction;
        double dxPreview;
        double dyPreview;
        double alignmentCost;
        double ghostFraction;
        double exposureScale = 1.0;
        double rawExposureScale = 1.0;
        byte[] rawConfidence;
        MotionField motionField;
        FrameInfo(Uri uri, Bitmap preview, double sharpness, double clippedFraction) {
            this.uri = uri;
            this.preview = preview;
            this.sharpness = sharpness;
            this.clippedFraction = clippedFraction;
        }
    }

    private static final class LocalShift {
        final double dx;
        final double dy;
        final double cost;
        final double texture;

        LocalShift(double dx, double dy, double cost, double texture) {
            this.dx = dx;
            this.dy = dy;
            this.cost = cost;
            this.texture = texture;
        }
    }

    private static final class MotionSample {
        final double u;
        final double v;
        final double dx;
        final double dy;
        final double cost;
        final double texture;
        double robustWeight = 1.0;

        MotionSample(float x, float y, int width, int height, LocalShift shift) {
            u = (x - width * 0.5) / Math.max(1.0, width);
            v = (y - height * 0.5) / Math.max(1.0, height);
            dx = shift.dx;
            dy = shift.dy;
            cost = shift.cost;
            texture = shift.texture;
        }
    }

    private static final class MotionField {
        final int previewWidth;
        final int previewHeight;
        final float minX;
        final float minY;
        final float maxX;
        final float maxY;
        final float[] shiftsX;
        final float[] shiftsY;
        final double medianCost;

        MotionField(int previewWidth, int previewHeight, float minX, float minY, float maxX, float maxY,
                    float[] shiftsX, float[] shiftsY, double medianCost) {
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.shiftsX = shiftsX;
            this.shiftsY = shiftsY;
            this.medianCost = medianCost;
        }

        static MotionField identity(int width, int height) {
            return translation(width, height, 0f, 0f, 0);
        }

        static MotionField translation(int width, int height, float dx, float dy, double cost) {
            float[] x = new float[MOTION_GRID_SIZE * MOTION_GRID_SIZE];
            float[] y = new float[MOTION_GRID_SIZE * MOTION_GRID_SIZE];
            java.util.Arrays.fill(x, dx);
            java.util.Arrays.fill(y, dy);
            return new MotionField(width, height, 0, 0,
                    Math.max(1, width - 1), Math.max(1, height - 1), x, y, cost);
        }

        void shiftAt(float previewX, float previewY, float[] output) {
            float gridX = (previewX - minX) / Math.max(1f, maxX - minX) * (MOTION_GRID_SIZE - 1);
            float gridY = (previewY - minY) / Math.max(1f, maxY - minY) * (MOTION_GRID_SIZE - 1);
            gridX = Math.max(0f, Math.min(MOTION_GRID_SIZE - 1f, gridX));
            gridY = Math.max(0f, Math.min(MOTION_GRID_SIZE - 1f, gridY));
            int x0 = (int)Math.floor(gridX);
            int y0 = (int)Math.floor(gridY);
            int x1 = Math.min(MOTION_GRID_SIZE - 1, x0 + 1);
            int y1 = Math.min(MOTION_GRID_SIZE - 1, y0 + 1);
            float fx = gridX - x0;
            float fy = gridY - y0;
            output[0] = interpolate(shiftsX, x0, y0, x1, y1, fx, fy);
            output[1] = interpolate(shiftsY, x0, y0, x1, y1, fx, fy);
        }

        void mapReferenceToCandidate(float referenceX, float referenceY, int fullWidth, int fullHeight,
                                     float[] output) {
            float previewX = referenceX * previewWidth / Math.max(1f, fullWidth);
            float previewY = referenceY * previewHeight / Math.max(1f, fullHeight);
            shiftAt(previewX, previewY, output);
            output[0] = referenceX + output[0] * fullWidth / Math.max(1f, previewWidth);
            output[1] = referenceY + output[1] * fullHeight / Math.max(1f, previewHeight);
        }

        void mapCandidateToReference(float candidateX, float candidateY, int fullWidth, int fullHeight,
                                     float[] output) {
            float referenceX = candidateX;
            float referenceY = candidateY;
            for (int iteration = 0; iteration < 2; iteration++) {
                float previewX = referenceX * previewWidth / Math.max(1f, fullWidth);
                float previewY = referenceY * previewHeight / Math.max(1f, fullHeight);
                shiftAt(previewX, previewY, output);
                referenceX = candidateX - output[0] * fullWidth / Math.max(1f, previewWidth);
                referenceY = candidateY - output[1] * fullHeight / Math.max(1f, previewHeight);
            }
            output[0] = referenceX;
            output[1] = referenceY;
        }

        float minimumShiftY() {
            float value = Float.POSITIVE_INFINITY;
            for (float shift : shiftsY) value = Math.min(value, shift);
            return value;
        }

        float maximumShiftY() {
            float value = Float.NEGATIVE_INFINITY;
            for (float shift : shiftsY) value = Math.max(value, shift);
            return value;
        }

        private static float interpolate(float[] values, int x0, int y0, int x1, int y1,
                                         float fx, float fy) {
            float top = values[y0 * MOTION_GRID_SIZE + x0]
                    + (values[y0 * MOTION_GRID_SIZE + x1] - values[y0 * MOTION_GRID_SIZE + x0]) * fx;
            float bottom = values[y1 * MOTION_GRID_SIZE + x0]
                    + (values[y1 * MOTION_GRID_SIZE + x1] - values[y1 * MOTION_GRID_SIZE + x0]) * fx;
            return top + (bottom - top) * fy;
        }
    }

    private enum OutputFormat {
        TIFF("TIFF — lossless RGB", "TIFF", "image/tiff", "tif"),
        DNG("DNG — lossless 16-bit LinearRaw", "DNG", "image/x-adobe-dng", "dng"),
        JPEG("JPEG — high quality, smaller file", "JPEG", "image/jpeg", "jpg");

        final String label;
        final String shortLabel;
        final String mimeType;
        final String extension;

        OutputFormat(String label, String shortLabel, String mimeType, String extension) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.mimeType = mimeType;
            this.extension = extension;
        }
    }

    private static final class ProcessingCancelledException extends Exception {
    }
}
