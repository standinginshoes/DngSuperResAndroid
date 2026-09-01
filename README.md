# DNG Super Resolution for Android

An offline Android burst processor that aligns DNG/image frames, rejects poor matches and local motion, merges useful image data, and exports an uncompressed RGB TIFF.

## Output modes

- **Same size (1×):** strongest practical noise reduction without enlarging the image.
- **1.5×:** recommended balance between denoising, detail recovery, memory, and processing time.
- **2×:** maximum output detail when the burst contains useful sub-pixel handheld offsets.

Higher resolution modes do not invent detail with a generative model. They combine differently shifted samples from the burst and interpolate the remaining output grid.

## What v0.4 does

- Android Storage Access Framework multi-select (no broad storage permission)
- DNG providers with non-image MIME types are supported; bursts are capped at 30 frames
- direct uncompressed Bayer DNG decoding, including MotionCam packed 10-bit DNGs
- disk-backed DNG input with row-sized read buffers to avoid whole-file heap allocations
- Android large-heap mode plus runtime memory checks for full-resolution fusion
- black/white-level correction, Bayer demosaic, AsShotNeutral white balance, and DNG color-matrix rendering
- Android `ImageDecoder` support for ordinary rendered image inputs
- automatic reference selection by Laplacian sharpness score
- higher-resolution coarse-to-fine gradient registration with fractional shift refinement
- per-frame exposure normalization and sharpness/alignment quality weighting
- whole-frame rejection for badly aligned or highly changed captures
- robust per-pixel weighting to suppress moving subjects, occlusions, and alignment errors
- selectable 1×, 1.5×, and 2× full-resolution fusion
- standards-compliant RGB TIFF export for the fused rendered image
- fully local/offline processing
- 70 MP and device-memory safety checks with a clear suggestion to select a lower mode

## Important limitation

The app now reads uncompressed Bayer DNG input directly, but it currently demosaics every frame before alignment and fusion. The output is therefore an 8-bit RGB TIFF, not a new sensor-raw DNG. A future engine should align and merge the original CFA samples, demosaic only once after fusion, and write a high-bit-depth TIFF or standards-compliant DNG. Compressed DNG variants are not yet supported by the built-in decoder.

The design is informed by Google's published burst photography and handheld multi-frame super-resolution work:

- [Handheld Multi-Frame Super-Resolution](https://research.google/pubs/handheld-multi-frame-super-resolution/)
- [Burst photography for high dynamic range and low-light imaging on mobile cameras](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/)

## Build

Open the repository root in a current Android Studio installation. Install/sync Android SDK 35 and the Android Gradle Plugin when prompted, then choose **Build > Build APK(s)**.

From a configured command line environment, run `./gradlew assembleDebug` from the repository root. If Gradle cannot find the SDK, set `ANDROID_HOME` or add a machine-local `local.properties` file containing `sdk.dir=/path/to/Android/Sdk`.

## Suggested next milestones

1. Remove generated desktop build artifacts from the vendored LibRaw tree and wire a minimal Android NDK build.
2. Native Bayer DNG ingest plus metadata viewer (CFA, black/white level, ISO, exposure, color matrices).
3. Pyramid and tile-local motion registration for rotation, parallax, and moving subjects.
4. CFA-domain kernel-regression fusion with confidence weights.
5. 16-bit TIFF and standards-compliant DNG output.
6. Vulkan/GPU compute path for alignment and reconstruction.
