# Phase 0 Execution Record

**Started:** 2026-07-13

This file separates implemented tooling from physical evidence. A green software build does not pass any device, image-quality, usability, legal, or licensing gate in `PLAN.md`.

## Delivered

### Benchmark v0

- Strict benchmark, annotation, and Camera2 capture-package schemas.
- Standard-library validator for IDs, split leakage, references, hashes, byte counts, capture status, frame ordering, per-frame payload/result roles, timestamp pairing, target roles, annotation asset types/dimensions, and confirmatory controls.
- Development fixture and negative unit tests.
- Capture, annotation, metrics, privacy, and preregistration procedures.
- Physical print as analysis unit and source group as leakage/resampling unit.

### Camera2 Prototype

- Direct rear-camera selection with RAW capability detection and YUV fallback.
- Preview plus one `RAW_SENSOR` or `YUV_420_888` `ImageReader` session.
- Preview convergence wait, explicit AE/AWB/AF lock attempt, independent timeouts, and per-frame actual metadata.
- Five non-interleaved `captureBurstRequests` requests.
- Exact `Image.timestamp` to `SENSOR_TIMESTAMP` pairing.
- Timestamp-matched DNG creation or canonical I420 repacking with strict even crop geometry.
- Separate delivered and persisted frame counts, failed pre-burst attempt records, file hashes, and validator-compatible capture manifests.
- Lifecycle generation guards and deferred `ImageReader` close while writers own images.

## Not Yet Executed

The workspace has no attached Android phone, prints, scanner, or physical quality target. The following remain unmeasured:

- stream combination success and RAW/YUV cadence on any phone
- OEM timestamp equality and `DngCreator` interoperability
- actual 3A/manual consistency and logical-camera switching
- ImageReader buffer pressure and partial/failure behavior
- memory, energy, thermal soak, and batch stability
- ARCore coexistence and CameraX sequential RAW fallback
- PhotoScan and flatbed comparisons
- glare, legitimate-highlight, color, geometry, and SFR measurements
- observed usability sessions
- counsel claim charts

## Device Matrix

Fill one row per physical camera/device/OS build. Do not merge results across updates.

| Device instance | Model/build | Camera ID | Hardware level | RAW | Manual sensor/post | Burst capability | Preview+RAW | Preview+YUV | 5-frame cadence | Drops | Peak memory | Thermal soak | DNG opens | Notes |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|
| pending | pending | pending | pending | pending | pending | pending | pending | pending | pending | pending | pending | pending | pending | No device attached |

Minimum first pass:

1. One recent flagship with RAW/manual/burst capabilities.
2. One mid-range device, including a YUV-only path if available.
3. Ten cold bursts and a 200-capture sustained batch per mode/device.
4. Validate every copied capture package before analysis.
5. Record failures and retries, not only successful packages.

## Benchmark Collection Milestones

| Milestone | Exit condition | Status |
|---|---|---|
| Protocol pilot | Five varied prints complete every modality and annotation path | Not started |
| Development v0 | At least 50 physical prints with characterized references and accepted packages | Not started |
| Analysis freeze | Endpoint, margin, comparators, failure rule, sample size, and source archive registered | Not started |
| Confirmatory collection | Quarantined `confirmatory-test` manifest passes validator | Not started |
| Gate A | Pre-registered superiority and no-regression rules pass | Not evaluated |
| Gate B/C | Capture and runtime limits pass target device matrix | Not evaluated |
| Gate D | Representative users complete capture reliably | Not evaluated |
| Gate E/F | Counsel claim charts and artifact BOM approvals complete | Not evaluated |

## Local Verification

```bash
python3 -m unittest discover -s benchmark/tests -v
python3 benchmark/tools/kirsch_benchmark.py validate \
  benchmark/tests/fixtures/development/manifest.json
./gradlew testDebugUnitTest assembleDebug lintDebug
```
