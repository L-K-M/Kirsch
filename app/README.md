# Kirsch Android App

Kirsch captures an immutable Camera2 acquisition package, processes accepted YUV packages locally, and records every output in a scan-level derivative graph. It does not upload data and the base manifest has no network permission.

## Scanning Flow

The main screen is a full-screen scanner: frame the photo, tap the shutter, then move the phone in slow circles while a four-segment coverage ring fills — the same interaction as fingerprint enrollment. Each ring segment (right, down, left, up) fills only when the accumulated motion reaches that direction's displacement target, so a one-directional wiggle cannot finish the sweep. Capture ends when all four directions are covered, the scan processes in the background, and the review screen opens automatically. Debug and benchmark controls live under Settings.

The ring is driven only by measured camera motion (subsampled phase correlation between consecutive frames); the guidance text is fixed, no visual target is placed at print corners or any other image position, and nothing in the capture flow reacts to glare or image content beyond a focus/stability gate. Those constraints are deliberate — see the patent dispositions in `PLAN.md`.

## Capture Profiles

- **Glare-removal sweep** is the default product path. `SweepPolicy` keeps frames that extend directional coverage and completes only once the kept views reach a displacement target in all four directions around the start (at least five frames, at most twenty-two, with a hard time limit). This replaces the fixed nine-frame burst: the 2026-07-14 GRL-AL10 audit showed pacing requests are advisory on non-manual-sensor devices, where nine requests span ~320ms and a few dozen pixels — far less than a typical specular footprint — and the first field sweeps showed that a single span target is satisfiable by one-directional motion. Directional coverage enforces perspective diversity on every device class.
- **Fixed 9-frame burst** remains available in Settings as the benchmark comparator.
- **RAW acquisition only** records a nine-frame DNG package when supported and falls back to YUV otherwise. RAW packages are retained but are not converted into product derivatives because Phase 0 did not verify a DNG demosaic, black-level, or color pipeline.
- **Quick single frame** records one YUV frame and uses the same review/export path without claiming glare reduction.

The controller prefers AWB lock over replaying result-reported gains, waits for successful focus, retains the lens lock when focus-distance metadata is missing, and limits RAW/YUV capture sizes to a 12–16 MP processing envelope. On manual-sensor devices, moving sweeps normally target 8.333 ms (1/120 second). Explicit Camera2 detection of 50 Hz lighting selects 10 ms (1/100 second); missing, NONE, and 60 Hz detection retain 8.333 ms. Already shorter exposures are preserved, ISO compensates for the shutter change, and post-RAW sensitivity boost is retained. When available ISO cannot preserve brightness, the controller lengthens exposure and records a low-light warning if it exceeds the selected budget. Devices without manual sensor control retain automatic exposure. Full-resolution patch sharpness rejects sustained blur. Kept frames record accumulated sweep position, analysis width, and sharpness. Sweep packages record the deliverable frame count as `requested_frame_count`, fixed at the moment the sweep stops.

This shutter adjustment targets detected 50 Hz mains lighting. General PWM lighting and anti-flicker period quantization of longer ISO-limited exposures remain outside this adjustment.

If a kept view is evicted before its `CaptureResult` arrives — the reader holds only six full-resolution buffers, so a slow write path can exhaust them — the loss is recorded as a warning and the deliverable count follows it down. A sweep degrades to a shorter stack rather than failing the whole capture, and the shortfall is visible in the manifest as the gap between `extensions.warnings` and the kept count.

## Processing

Accepted YUV acquisitions enter a process-death-recoverable single-worker queue. Processing:

1. verifies every selected payload and metadata file against its recorded byte count and SHA-256
2. selects up to five views covering the origin and directional extrema, then fills by displacement diversity; older packages without sweep positions retain time-spaced selection
3. selects the sharpest measured frame as reference and rejects substantially blurred views
4. registers views with ORB and MAGSAC++ homographies, rejecting localized matches, implausible geometry, and excessive reprojection error
5. matches rendered brightness from robust corresponding pixels; sensor exposure products remain evidence, rather than being multiplied into already tone-mapped YUV pixels
6. applies conservative glare-aware temporal selection and averages only views that agree in both brightness and color; confidence counts actual contributors and validity excludes interpolation across image borders
7. detects print quadrilaterals and rectifies the largest candidate using recorded camera geometry where available, including single-axis tilt; manual correction uses the same calibration
8. writes a high-quality JPEG and a 16-bit TIFF container, with native allocations released on processing failures

Camera crop coordinates start at the selected sensor array's origin, and lens calibration coordinates start at the pre-correction array's origin. Capture metadata records the actual distortion correction mode: OFF uses the pre-correction active array, while FAST and HIGH_QUALITY use the active array. Missing or unknown mode with differing arrays declines intrinsics. Pre-correction calibration is used only when it matches the selected array basis and has valid parameters with negligible skew; otherwise the recorded focal length and physical sensor size provide an estimate where available. Sensor and per-stream aspect cropping are mapped to the full output stream dimensions recorded in characteristics as `capture_size`. The packed `Image.cropRect` origin is then subtracted from the principal point without another rescale. Malformed or out-of-bounds sensor and packed crop metadata declines intrinsics.

The TIFF records its source bit depth separately. An 8-bit YUV acquisition stored in a 16-bit container is not represented as a 16-bit capture.

## Review And Derivatives

Review first shows the actual processed output, with pinch and double-tap zoom and its pixel dimensions. It explains automatic-crop failure and single-frame fallback. Accepted scans reopen the version actually saved. Failed processing appears in the library with a retry action using the retained capture.

The review screen provides draggable print corners (with a magnifier loupe while dragging, and a grab radius so stray taps cannot move a corner), optional archival physical-scale metadata, and explicit restored derivatives:

- descreening
- dust/scratch removal
- fade correction
- classical 2× upscaling

Corner drafts survive screen recreation. Save, rotation, and restoration wait until changed corners are applied, so the displayed draft cannot be silently discarded. Rotate clockwise creates a new JPEG and lossless TIFF companion; later cropping preserves that orientation, and repeated turns use the lossless companion. Confirmed physical dimensions swap with the output axes.

Live edits and saves survive screen recreation without reopening controls early. The replacement screen reloads the committed image before becoming editable, and failed edits preserve the corner draft. Returning to a previously open review also refreshes the saved version. Gallery export uses application context and leaves the source derivative unchanged.

Restorations never overwrite the acquisition-derived master: each is written as a new file and appended to the derivative graph. Creating one does make it the scan's *active* output, so review and **Save to Photos** show what was asked for; **Use original scan** returns the active output to the newest unrestored copy without deleting anything. Every derivative records its recipe, parent path/hash, output hash, and creation time. Accepted scan revisions are immutable.

Recorded physical scale survives edits that change pixel dimensions: the print's confirmed size does not change when the active output does, so sampling frequency is re-derived from the new dimensions rather than dropped.

**Save to Photos** finishes a scan: when more than one exportable version exists it asks which to save (the active output is preselected), the chosen JPEG is inserted into the device photo library under `Pictures/Kirsch` via MediaStore with a dated display name and EXIF capture metadata (no extra permission required for app-created media), the scan is accepted and locked, and the export with its source path is recorded in the scan manifest's `extensions`. The full-fidelity TIFF and all sources stay in app storage.

Sampling frequency is labeled PPI only after confirmed dimensions or a traceable coplanar target are recorded. This does not claim delivered SFR resolution.

## Unavailable Capabilities

The capabilities screen gives recorded reasons for unavailable MFSR, learned residual models, curl correction, album splitting, handwriting recognition, learned illuminant estimation, generative restoration, C2PA, and cloud processing. These paths fail closed; the app does not fabricate placeholder results.

## Storage And Export

```text
Android/data/ch.lkmc.kirsch/files/
  captures/capture-.../           # immutable acquisition package
  scans/product/capture-.../
    scan.json                     # state and derivative graph
    processing-report.json
    working/fused.png
    derivatives/
      acquisition-master.jpg
      acquisition-master.tif
      confidence.png
      failure.png
      restored-*.jpg
```

Storage Access Framework export (Settings → Export packages) places paired sources under `acquisitions/` and products under `scans/`. In-progress and `.partial` files are excluded.

## Build

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew connectedDebugAndroidTest # running emulator or connected device
```

To build and install the debug APK on a phone connected over adb in one step:

```bash
scripts/install-debug.sh              # single connected device
scripts/install-debug.sh <serial>     # pick one of several (see `adb devices`)
```

OpenCV 4.10.0 is the only new runtime artifact and is recorded in `benchmark/ARTIFACTS.csv`.

Exported scan graphs can be checked without OpenCV:

```bash
python3 benchmark/tools/kirsch_benchmark.py validate-scan /path/to/scan.json
```

Native emulator tests exercise the actual OpenCV registration/fusion pipeline, I420 processing, manual cropping, rotation, visible review pixels, crop drafts, and save-chooser state. CI runs these tests separately from JVM policies. Synthetic moving-glare tests are regression evidence; they do not establish superior quality on physical glossy prints. Use the benchmark capture protocol to compare current Kirsch, the phone camera, and PhotoScan under matched lighting and framing.
