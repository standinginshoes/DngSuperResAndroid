# DNG Super Resolution for Android

An offline Android burst processor that aligns DNG/image frames, rejects poor matches and local motion, merges useful image data, and exports DNG, TIFF, or JPEG.

## Output resolution

- **100% (1×) is the default:** strongest practical noise reduction without enlarging the image.
- **Variable 100–200% sizing:** choose any output scale in 5% steps.
- **150%:** a useful balance between denoising, detail recovery, memory, and processing time.
- **200%:** maximum output detail when the burst contains useful sub-pixel handheld offsets.

Higher resolution modes do not invent detail with a generative model. They combine differently shifted samples from the burst and interpolate the remaining output grid.

## Output formats

- **DNG:** uncompressed 16-bit LinearRaw containing the rendered merged RGB result.
- **TIFF:** uncompressed 8-bit RGB.
- **JPEG:** high-quality (95) compressed RGB for smaller files.

The DNG output is a standards-based LinearRaw DNG, not the original Bayer sensor mosaic. The merge currently works on rendered RGB frames, so exporting to 16-bit DNG preserves a lossless linear container but cannot recreate sensor precision that was discarded during 8-bit rendering.

## What v0.7 does

- Android Storage Access Framework multi-select (no broad storage permission)
- DNG providers with non-image MIME types are supported; bursts are capped at 30 frames
- direct uncompressed Bayer DNG decoding, including MotionCam packed 10-bit DNGs
- uncompressed RGB and LinearRaw DNG decoding for already-demosaiced camera output
- CFA-metadata detection for files with nonstandard photometric tagging
- disk-backed DNG input with row-sized read buffers to avoid whole-file heap allocations
- Android large-heap mode plus runtime memory checks for full-resolution fusion
- black/white-level correction, Bayer demosaic, AsShotNeutral white balance, and DNG color-matrix rendering
- Android `ImageDecoder` support for ordinary rendered image inputs
- automatic reference selection by Laplacian sharpness score
- exposure-normalized coarse-to-fine gradient registration with fractional shift refinement
- robust 5×5 local motion fields that model rotation, small scale changes, parallax, and local camera motion
- piecewise-bilinear sub-pixel warping instead of applying one translation to the whole frame
- per-frame exposure normalization and sharpness/alignment quality weighting
- highlight-aware reference selection and contribution weighting
- whole-frame rejection for badly aligned or highly changed captures
- locally warped per-pixel robustness masks to suppress moving subjects, occlusions, and clipped samples
- variable 100–200% full-resolution fusion in 5% steps
- selectable 16-bit LinearRaw DNG, uncompressed RGB TIFF, or high-quality JPEG export
- fully local/offline processing
- 70 MP and device-memory safety checks with a clear suggestion to select a lower mode

## Important limitation

The app reads uncompressed Bayer DNG input directly, but it currently demosaics every frame before alignment and fusion. Its DNG export is therefore 16-bit LinearRaw RGB, not a new sensor-raw Bayer DNG. A future engine should align and merge the original CFA samples and demosaic only once after fusion. Compressed DNG variants are not yet supported by the built-in decoder.

The next major quality step is a native, tile-streamed CFA-domain kernel-regression engine. That architecture would retain the source bit depth, merge red/green/blue Bayer samples before demosaicing, steer reconstruction kernels along local edges, and normalize accumulated sample weights. v0.7 adopts its local-alignment and robustness principles while retaining the current memory-safe rendered-RGB pipeline.

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
