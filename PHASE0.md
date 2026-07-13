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

### First Device Evidence (2026-07-13)

Eleven capture packages from a HUAWEI GRL-AL10 (one with full frame payloads) were audited; see [`benchmark/reports/2026-07-13-grl-al10-capture-audit.md`](benchmark/reports/2026-07-13-grl-al10-capture-audit.md). Measured on that device: YUV stream combination and cadence (RAW absent — the fallback path was exercised), 3A lock behavior (AE lock reported `LOCKED` while the HAL swapped exposure/ISO at a constant exposure×gain product), payload integrity, and an ORB+MAGSAC++ registration/fusion baseline (~1.2px mean residual). One defect found and fixed: replaying HAL-reported color-correction gains with AWB off baked an implausible white balance into the burst (`WhiteBalanceStrategy` now prefers AWB lock). The audited captures contained no glare and no camera motion, so they qualify the capture path only — not Gate A. Offline analysis commands: `benchmark/tools/analyze_capture.py` (`audit`, `fuse`; requires numpy and opencv-python-headless, recorded in `ARTIFACTS.csv`).

A second device entered the matrix via the in-app zip export: an HMD Fusion (LEVEL_3; RAW, manual sensor, and burst capabilities all present — the first device able to exercise the full linear-RAW quality path). Evidence so far: steady 30fps cadence in both YUV and RAW modes; the manual-sensor exposure path engaged (AE off with explicit values); AWB lock honored with plausible reported gains; a `raw-sensor` burst persists and audits clean (DNG decode still unverified — the analysis tool is I420-only); and owner-run YUV fusion reproduced the min-equals-floor optimality result seen on the GRL-AL10 (0.9747% residual against a 0.9747% floor). A harmless "3A lock timed out" warning revealed that the lock-wait predicate should not expect `AE_STATE_LOCKED` on the manual-sensor path (candidate controller fix). See [`benchmark/reports/2026-07-13-hmd-fusion-capture-audit.md`](benchmark/reports/2026-07-13-hmd-fusion-capture-audit.md).

## Not Yet Executed

The following remain unmeasured:

- RAW payload verification: HMD Fusion RAW bursts persist and audit clean at 33.3ms cadence, but the DNG files have not been decoded or fused (analysis tool is I420-only)
- OEM timestamp equality and `DngCreator` interoperability
- logical-camera switching behavior beyond the single audited device
- ImageReader buffer pressure and partial/failure behavior
- memory, energy, thermal soak, and 200-capture batch stability
- ARCore coexistence and CameraX sequential RAW fallback (ARCore is unavailable on the audited HarmonyOS-based device)
- PhotoScan and flatbed comparisons
- glare, legitimate-highlight, color, geometry, and SFR measurements (audited captures had no glare and no sweep motion)
- observed usability sessions
- counsel claim charts

## Device Matrix

Fill one row per physical camera/device/OS build. Do not merge results across updates.

| Device instance | Model/build | Camera ID | Hardware level | RAW | Manual sensor/post | Burst capability | Preview+RAW | Preview+YUV | 5-frame cadence | Drops | Peak memory | Thermal soak | DNG opens | Notes |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|
| owner-primary | HUAWEI GRL-AL10, Kirin 9010, Android 12 / API 31, build 104.2.0.201C00 | 0 (bursts on physical 2) | LIMITED | no | no / yes | no | n/a (no RAW) | yes, 3344x3072 I420 | 42ms (~24fps) x10 bursts; 50-53ms x1 | 0 in 11 bursts | pending | pending | n/a (no RAW) | AE/AWB lock available; AE lock swaps exposure/ISO at constant product; WB replay defect found and fixed; no GMS (no ARCore/ML Kit); see 2026-07-13 audit report |
| owner-secondary | HMD Fusion (Nighthawk), Qualcomm, Android 15 / API 35, build 00WW_2_83B | 0 | LEVEL_3 | yes | yes / yes | yes | yes, 33.3ms (owner-run audit) | yes, 4000x3000 I420 | 33.3ms (30fps), RAW and YUV | 0 in 2 bursts (YUV `...c9834f8c`, RAW `...29656dcb`) | pending | pending | packages persist + audit clean; DNG decode not yet verified | Manual-sensor exposure path engaged (AE off + explicit values); AWB lock honored; HAL reports plausible gains and real CCM; "3A lock timed out" warning is expected on the manual-sensor path (predicate fix candidate); YUV fusion reproduces min==floor optimality; see 2026-07-13 HMD report |

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
