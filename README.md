# DNG Super Resolution for Android

An offline Android burst processor that aligns DNG/image frames, rejects poor matches and local motion, merges useful image data, and exports DNG, TIFF, or JPEG.

## Output resolution

- **100% (1×) is the default:** strongest practical noise reduction without enlarging the image.
- **Variable 100–200% sizing:** choose any output scale in 5% steps.
- **150%:** a useful balance between denoising, detail recovery, memory, and processing time.
- **200%:** maximum output detail when the burst contains useful sub-pixel handheld offsets.

Higher resolution modes do not invent detail with a generative model. The Bayer merge combines aligned samples from the burst at native resolution, then resamples the developed result to the selected dimensions.

## Output formats

- **DNG:** uncompressed 16-bit LinearRaw containing the rendered merged RGB result.
- **TIFF:** uncompressed 8-bit RGB.
- **JPEG:** high-quality (95) compressed RGB for smaller files.

The DNG output is a standards-based 16-bit LinearRaw DNG containing the developed merge, not a Bayer mosaic DNG. Bayer inputs are fused before demosaic, but the current Android bitmap result is rendered to sRGB before export; TIFF and DNG are therefore lossless containers for the rendered result rather than untouched sensor samples.

## What v0.8 does

- Android Storage Access Framework multi-select (no broad storage permission)
- DNG providers with non-image MIME types are supported; bursts are capped at 30 frames
- direct uncompressed Bayer DNG decoding, including MotionCam packed 10-bit DNGs
- uncompressed RGB and LinearRaw DNG decoding for already-demosaiced camera output
- CFA-metadata detection for files with nonstandard photometric tagging
- disk-backed DNG input with row-sized read buffers to avoid whole-file heap allocations
- tile-streamed Bayer CFA fusion that reads only the sensor rows required by each output stripe
- per-frame linear-RAW exposure measurement using the DNG black and white levels
- phase-preserving sub-pixel sampling on each Bayer color lattice before normalized fusion
- full-resolution RAW-domain robust weights in addition to preview motion masks, reducing moving-subject ghosts
- one edge-directed demosaic after stacking instead of demosaicing every input frame
- automatic rendered-RGB compatibility fallback for unsupported DNG/image layouts
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

## Important limitations

The RAW engine currently supports uncompressed 8–16-bit, standard 2×2 Bayer DNGs. Compressed DNGs, non-Bayer mosaics, differently sized frames, and rendered image inputs use the v0.7 RGB compatibility path. The four MotionCam-style packed 10-bit DNGs included in this project are the primary v0.8 target.

The merge is CFA-domain and preserves normalized sensor precision internally, but its saved DNG is currently developed 16-bit LinearRaw RGB rather than a newly encoded Bayer mosaic. A future native kernel-regression/Vulkan path could reconstruct directly onto a higher-resolution grid, add lens-shading and bad-pixel metadata handling, and stream a fused Bayer DNG without the Android bitmap stage.

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
4. Steered kernel-regression reconstruction directly on the selected output grid.
5. Fused Bayer DNG plus true 16-bit RGB TIFF output.
6. Vulkan/GPU compute path for alignment and reconstruction.
