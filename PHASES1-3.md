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

### Amendment (2026-07-14): displacement-driven sweep

The GRL-AL10 product sweep audit
([`benchmark/reports/2026-07-14-grl-al10-product-sweep-audit.md`](benchmark/reports/2026-07-14-grl-al10-product-sweep-audit.md))
showed that a fixed nine-request burst is not a sweep on devices without
manual sensor control: frames arrived at the native 40ms cadence, the
28-43px view displacement stayed far below the specular footprint, and
fusion — while still exactly reaching the all-frame saturation floor —
could not remove the dominant glare core.

In response, the default product capture is now a displacement-driven
freehand sweep (`SweepPolicy` + `SweepFrameAnalyzer`): a repeating capture
runs while the user moves the phone, frames are kept only when they are
displaced from the last kept frame and pass a relative-sharpness gate, and
the capture completes when the kept views span a target fraction of the
frame (minimum five frames, maximum twelve, hard time limit). Progress
shown to the user is a function of measured camera motion only — the
patent-relevant constraint that capture guidance is never glare-driven is
preserved. The fixed nine-frame burst remains selectable as the benchmark
comparator. Whether the enforced displacement is sufficient on real glossy
prints is a physical question for the next capture round, not something a
green build establishes.

Same-day field feedback from the first product sweeps tightened this
further: a single displacement-span target was satisfiable by
one-directional motion, which does not diversify perspectives across the
print. Completion now requires directional coverage — the kept views must
reach a displacement target in each of four directions around the sweep's
start — shown as a four-segment ring. The constraint remains motion-only:
no visual target is placed at print corners (deliberately unlike the
corner-dot pattern in the Google Family B claims), and nothing in guidance
reacts to glare. Finishing a scan now also exports the output JPEG to the
device photo library via MediaStore, recorded in the scan manifest's
`extensions`.

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
