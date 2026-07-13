# Capture audit: HUAWEI GRL-AL10, 2026-07-13

First physical-device evidence for the Phase 0 device matrix. Source data:
eleven capture packages (manifests + characteristics) plus one complete
package with frame payloads (`capture-20260713t174009965z-be3820d8`),
captured on the project owner's primary phone. Analysis commands are
reproducible via `benchmark/tools/analyze_capture.py` (`audit` and `fuse`).

## Device

| Field | Value |
|---|---|
| Model / build | HUAWEI GRL-AL10, Kirin 9010, Android 12 (API 31), fingerprint `HUAWEI/GRL-AL10D/HWGRL:12/.../104.2.0.201C00` |
| Camera | id 0 (logical; bursts routed to physical id 2), rear |
| Hardware level | LIMITED (0) |
| Capabilities | BACKWARD_COMPATIBLE, MANUAL_POST_PROCESSING, LOGICAL_MULTI_CAMERA, DEPTH_OUTPUT, CONSTRAINED_HIGH_SPEED_VIDEO |
| RAW / manual sensor / burst capability | none / none / none |
| AE lock / AWB lock | available / available |
| Sensor | 8192x6144 (50MP), white level 4095 (12-bit) |
| YUV capture stream | 3344x3072 I420, min frame duration 33.3ms, stall 0 |

## Capture behavior (11 cold bursts)

- Every burst requested `raw-sensor` and fell back to `yuv-420-888` with the
  expected warning. The capability-driven fallback worked as designed.
- 5/5 frames received and persisted in all 11 bursts; zero errors.
- Inter-frame cadence 42ms (~24fps) in 10 bursts; 50–53ms in one.
- Wall time per burst 1.7–2.6s including persistence.

## Full-package audit (be3820d8)

- **AE lock quirk:** AE lock requested and reported `LOCKED`, yet the HAL
  swapped exposure/ISO mid-burst (33.332ms @ ISO 649 for frames 0–1,
  30.0ms @ ISO 721 for frames 2–4). The exposure×gain product stayed
  constant to 0.011%, so the frames are radiometrically consistent, but the
  `LOCKED` state alone must not be trusted — normalize by the product.
- **White-balance defect (fixed in app):** the burst replayed
  preview-reported `COLOR_CORRECTION_GAINS` `[1.933, 1.946, 1, 1]` with
  `AWB_MODE_OFF`. A ~49% split between the green channels and unity blue
  gain under warm light are not plausible AWB output; the replay baked a
  severe, unrecoverable yellow-green cast into the YUV (U-plane mean 58.6
  vs neutral 128). On a RAW-less device this cannot be corrected
  downstream. Fix: prefer `CONTROL_AWB_LOCK` (available on this device)
  over manual replay; replay only when lock is unavailable and reported
  gains pass a plausibility check (`WhiteBalanceStrategy`).
- **Payload integrity:** files are true planar I420 (uniform even/odd
  chroma statistics), stride handling correct. Focus locked at a constant
  6.756 diopters (~15cm); flash never fired; timestamps monotonic.
- **Registration baseline:** ORB (8k features) + MAGSAC++ homography to the
  middle frame: 1,940–2,154 inliers per frame, mean reprojection residual
  1.14–1.27px. Baseline fusion composites (min / soft-min / median) built
  without artifacts.
- **Rolling shutter:** 13.7ms skew per frame — bound sweep speed or model
  the shear when real sweeps start.

## Protocol gaps (for the next capture round)

1. **No glare in the scene** (0.008% saturated pixels; 0.0035% saturated in
   every frame). The glare-displacement hypothesis is untested by this
   data. Gate A captures need a glossy print under a visible glare source.
2. **No camera motion** (4.1–7.7px center shift across the whole burst).
   Glare displacement needs a deliberate sweep. Because this device streams
   YUV at a steady ~24fps with zero stall, capture 30–40 frames across a
   1.5–2s sweep and select by sharpness/baseline instead of relying on a
   5-frame burst.
3. Scene was a bench object, not a photographic print.

## Platform notes for this device class

HarmonyOS-based Huawei builds lack Google Play services: ML Kit, ARCore,
and Play AI Packs are unavailable, and LiteRT NPU delegates do not cover
Kirin (GPU/CPU paths only). Pose priors fall back to IMU sensor fusion and
model delivery needs a non-Play channel if this device class remains a
target.

## Follow-up: fixed-build captures (same day)

Two further glare captures (94f1cc07, 6b0e39e8) taken with the
`WhiteBalanceStrategy` build confirm the fix in the field: per-frame
metadata shows `awb_mode=AUTO` with the lock requested and
`awb_state=LOCKED`, and color is correct. The HAL still reports the same
implausible gain pattern in results under locked AWB, confirming that
result-reported gains are untrustworthy on this device regardless of mode
(the audit tool now distinguishes the applied-with-AWB-off defect from the
informational case).

Fusion results on the two captures (plus two pre-fix glare captures,
d1a36663 and c9aa5587): with real sweep motion (40–217px center shift),
the per-pixel minimum's residual saturated area equals the
saturated-in-every-frame floor exactly in both measurable cases
(2.00% → 1.30% against a 1.30% floor; 0.073% → 0.034% against a 0.034%
floor) — the estimator recovers everything the capture geometry allows.
The residual glare core is a capture problem, not an estimator problem:
the sweep displacement must exceed the specular blob's extent. Guidance
for the next round: wider/tilted sweeps or more frames, and evaluate glare
metrics inside print-region masks (whole-frame percentages are diluted by
background, and pre-WB-fix captures need peak-channel rather than
channel-mean detection because the cast suppresses blue).

The capture app now includes a Storage Access Framework zip export
(`CapturePackageZipper`) so packages can leave devices whose
`Android/data` is not browsable — previously a blocker for qualifying any
secondary device in the matrix.
