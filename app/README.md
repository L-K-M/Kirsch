# Kirsch Android App

Kirsch captures an immutable Camera2 acquisition package, processes accepted YUV packages locally, and records every output in a scan-level derivative graph. It does not upload data and the base manifest has no network permission.

## Scanning Flow

The main screen is a full-screen scanner: frame the photo, tap the shutter, then move the phone slowly across the print while a progress ring fills — the same interaction as fingerprint enrollment. Capture ends when enough view displacement has been gathered, the scan processes in the background, and the review screen opens automatically. Debug and benchmark controls live under Settings.

The progress ring is driven only by measured camera motion (subsampled phase correlation between consecutive frames); the guidance text is fixed and nothing in the capture flow reacts to glare or image content beyond a focus/stability gate. That constraint is deliberate — see the patent dispositions in `PLAN.md`.

## Capture Profiles

- **Glare-removal sweep** is the default product path. `SweepPolicy` keeps frames spaced by measured displacement and completes only once the kept views span a set fraction of the frame (at least five frames, at most twelve, with a hard time limit). This replaces the fixed nine-frame burst: the 2026-07-14 GRL-AL10 audit showed pacing requests are advisory on non-manual-sensor devices, where nine requests span ~320ms and a few dozen pixels — far less than a typical specular footprint. Displacement gating enforces the geometry on every device class.
- **Fixed 9-frame burst** remains available in Settings as the benchmark comparator.
- **RAW acquisition only** records a nine-frame DNG package when supported and falls back to YUV otherwise. RAW packages are retained but are not converted into product derivatives because Phase 0 did not verify a DNG demosaic, black-level, or color pipeline.
- **Quick single frame** records one YUV frame and uses the same review/export path without claiming glare reduction.

The controller prefers AWB lock over replaying result-reported gains, fixes the HMD Fusion manual-AE lock wait, records actual per-frame metadata, and limits RAW/YUV capture sizes to a 12–16 MP processing envelope. Sweep packages record the kept-frame count as `requested_frame_count`, fixed at the moment the sweep stops.

## Processing

Accepted YUV acquisitions enter a process-death-recoverable single-worker queue. Processing:

1. verifies every selected payload and metadata file against its recorded byte count and SHA-256
2. selects up to five evenly spaced frames from the acquisition to bound native memory (sweep keeps are already displacement-spaced)
3. normalizes by exposure-time × sensitivity where metadata permits
4. registers frames to the middle observation with ORB and MAGSAC++ homographies
5. rejects weak registration and falls back visibly to the best single frame
6. applies conservative glare-aware temporal selection and emits confidence/failure maps
7. detects print quadrilaterals, rectifies the largest candidate, and always permits manual correction
8. writes a high-quality JPEG and a 16-bit TIFF container

The TIFF records its source bit depth separately. An 8-bit YUV acquisition stored in a 16-bit container is not represented as a 16-bit capture.

## Review And Derivatives

The review screen provides draggable print corners, acceptance, archival physical-scale metadata, and explicit restored derivatives:

- descreening
- dust/scratch removal
- fade correction
- classical 2× upscaling

Restorations never overwrite the acquisition-derived master. Every derivative records its recipe, parent path/hash, output hash, and creation time. Accepted scan revisions are immutable.

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
