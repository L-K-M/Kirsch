# Phases 1–3 Implementation Record

**Implemented:** 2026-07-13

This record maps product code to the roadmap while preserving the Phase 0 findings. It distinguishes executable local features from roadmap candidates that require artifacts or operational systems absent from the repository.

## Phase 1

Implemented:

- Camera2 capture profiles for RAW acquisition, YUV quality sweeps, and quick single-frame fallback.
- Nine-frame quality acquisition with manual-sensor pacing, capability-driven RAW-to-YUV fallback, AWB-lock preference, and actual timestamp/3A metadata.
- HMD Fusion fix: manual-sensor captures no longer wait for an impossible `AE_STATE_LOCKED` result.
- Atomic capture packages and a persistent, independent-scan processing queue.
- Input byte-count/hash verification and bounded five-frame processing selection.
- ORB matching, MAGSAC++ homographies, exposure-product normalization, native-size conservative temporal fusion, and explicit confidence/failure maps.
- Registration failure fallback to a single frame rather than silent merging.
- Automatic multi-quad suggestions, largest-print rectification, and draggable manual corner correction.
- JPEG derivative, TIFF container, processing report, acquisition reference, and hashed derivative graph.
- Paired acquisition/product SAF export, lifecycle-safe queue recovery, latency/heap/thermal instrumentation, and immutable accepted revisions.

Evidence boundary:

- RAW/DNG acquisition is implemented and retained, but product RAW conversion is disabled. Phase 0 did not verify DNG decoding, demosaic, black-level correction, color transforms, or high-bit-depth output. Promoting decoded 8-bit pixels is not treated as a RAW pipeline.
- The default product path is therefore the evidenced YUV path. Its TIFF stores 16-bit samples but records the effective 8-bit source depth.

## Phase 2

Implemented local/evidenced paths:

- Temporal residual de-glare in the conservative fusion path.
- Multiple print-region suggestions in the processing report without automatic album splitting.
- Physical-scale archival metadata from confirmed dimensions or a traceable coplanar target, with PPI terminology and no automatic delivered-resolution claim.
- High-contrast scalable Android UI, accessibility labels/live status, and manual correction.

Unavailable and fail-closed:

- MFSR, print-specific learned residual de-glare, learned curl correction, album segmentation, camera handwriting recognition, and learned illuminant estimation.
- No approved checkpoint, mobile implementation, or required fidelity/license evidence for these paths exists in the repository. `FeatureCatalog` exposes the reason and no output is generated.

## Phase 3

Implemented:

- Non-destructive restored derivatives for descreening, dust/scratch inpainting, fade correction, and classical upscaling.
- Recipe, parent hash, output hash, revision state, and acquisition/restored classification for every output.
- A generative gate that requires feature availability and identity/landmark evidence for face-affecting output.

Unavailable and fail-closed:

- Prior-driven super-resolution, face restoration, generative inpainting, and colorization have no approved weights or identity evaluation artifacts.
- C2PA has no signing identity, certificate lifecycle, timestamping, or revocation service.
- Cloud processing has no reviewed backend, attestation, economics, or deletion contract; the app requests no network permission.

These are represented by explicit unavailable providers, not no-op implementations or mislabeled deterministic output.

## Verification Contract

```bash
python3 -m unittest discover -s benchmark/tests -v
python3 benchmark/tools/kirsch_benchmark.py validate \
  benchmark/tests/fixtures/development/manifest.json
python3 benchmark/tools/kirsch_benchmark.py validate-scan /path/to/scan.json
./gradlew testDebugUnitTest assembleDebug lintDebug
```

`benchmark/schema/scan-package-v1.schema.json` documents the product scan and derivative graph. Hardware image-quality claims still require physical captures; this implementation record does not alter `PHASE0.md` evidence.
