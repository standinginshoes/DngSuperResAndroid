# DNG Super Resolution (Android MVP)

An offline Android proof-of-concept that accepts a burst of DNG/image frames, automatically picks a sharp reference frame, estimates translation alignment on grayscale previews, projects the full frames onto a 2× output canvas, incrementally averages them, and exports a lossless PNG.

## What v0.1 does

- Android Storage Access Framework multi-select (no broad storage permission)
- DNG providers with non-image MIME types are supported; bursts are capped at 30 frames
- DNG/image decode through Android `ImageDecoder`
- automatic reference selection by Laplacian sharpness score
- coarse-to-fine gradient-based translational registration, less sensitive to exposure differences
- robust frame rejection based on post-alignment residuals to reduce blur and ghosting
- fractional shift refinement using a parabolic fit around the alignment minimum
- 2× full-resolution projection and sharpness-weighted incremental fusion
- standards-compliant RGB TIFF export for the fused rendered image
- fully local/offline processing
- output size is limited by the device's available memory; allocation failures are reported clearly

## Important limitation

This is the runnable MVP processing path, **not yet true Bayer/CFA RAW super-resolution**. The Pixel DNGs used by this app contain rendered RGB data, so the result is exported as a standards-compliant RGB TIFF. The next engine should use a native DNG/RAW decoder (for example LibRaw) with Bayer DNG input to extract original mosaic samples, preserve black/white levels and CFA metadata, perform local motion/ghost rejection, reconstruct the 2× Bayer plane, demosaic once at the end, and write a high-resolution sensor-raw DNG.

## Build

Open the repository root in a current Android Studio installation. Install/sync Android SDK 35 and the Android Gradle Plugin when prompted, then choose **Build > Build APK(s)**.

From a configured command line environment, run `./gradlew assembleDebug` from the repository root. If Gradle cannot find the SDK, set `ANDROID_HOME` or add a machine-local `local.properties` file containing `sdk.dir=/path/to/Android/Sdk`.

## Suggested next milestones

1. Native LibRaw DNG ingest via NDK/JNI.
2. RAW metadata viewer (CFA, black/white level, ISO, exposure, color matrices).
3. Pyramid + tile/local-motion registration.
4. Robust outlier/ghost rejection.
5. CFA-domain 2× fusion with confidence weights.
6. 16-bit TIFF and standards-compliant DNG writer.
7. Vulkan/GPU compute path for alignment and reconstruction.
