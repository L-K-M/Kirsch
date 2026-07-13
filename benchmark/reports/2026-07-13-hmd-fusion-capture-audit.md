# Capture audit: HMD Fusion, 2026-07-13

Second device-matrix entry. Evidence base is intentionally thin — one
YUV capture manifest (`capture-20260713t191440699z-c9834f8c`) with the
camera characteristics and a single frame's metadata, exported through the
new in-app SAF zip path — so this records capabilities and first
observations, not qualification.

## Device

| Field | Value |
|---|---|
| Model / build | HMD Fusion (Nighthawk), Qualcomm, Android 15 (API 35), build `00WW_2_83B` |
| Camera | id 0, rear |
| Hardware level | LEVEL_3 (highest) |
| Capabilities | BACKWARD_COMPATIBLE, **RAW**, **MANUAL_SENSOR**, **BURST_CAPTURE**, MANUAL_POST_PROCESSING, READ_SENSOR_SETTINGS, YUV/PRIVATE_REPROCESSING, CONSTRAINED_HIGH_SPEED |
| AE lock / AWB lock | available / available |
| Sensor | 4000x3000 (12MP), white level 1023 (10-bit) |
| YUV capture stream | 4000x3000 I420, min frame duration 33.3ms, stall 0 |

This is the first matrix device with the full quality path from PLAN.md:
RAW capture, manual sensor control, and burst capability all present —
everything the GRL-AL10 lacks. The linear-RAW pipeline can be exercised
end to end on this hardware (`DngCreator` interoperability remains an
open Phase 0 item).

## Observations from the YUV capture

- 5/5 frames received and persisted; zero errors. Inter-frame cadence a
  steady 33.31ms (~30fps) — faster and more regular than the GRL-AL10's
  42ms. Persistence dominated wall time (~5.0s for ~60MB of I420).
- **Manual-sensor exposure path engaged:** per-frame metadata shows
  `ae_mode=OFF` with explicit exposure (20ms @ ISO 116) rather than an AE
  lock — the controller's preferred path when MANUAL_SENSOR is available,
  stronger than a lock.
- **AWB lock path engaged and honored:** `awb_mode=AUTO`,
  `awb_lock_requested=true`, `awb_state=LOCKED`. Unlike the GRL-AL10,
  this HAL reports *plausible* color-correction gains (R 1.02, G 1.0/1.0,
  B 2.27 under warm light) and a real color matrix — result metadata
  looks trustworthy on this device.
- **"3A lock timed out" warning** was recorded while the per-frame states
  above look correct. Likely cause: the lock-wait predicate expects
  `AE_STATE_LOCKED`, but on the manual-sensor path AE reports `INACTIVE`
  (mode OFF), so the wait can only time out. Harmless here by design
  (per-frame states are authoritative), but the lock plan should treat
  the manual-sensor path as not requiring an AE lock state — candidate
  small fix in the controller.
- Focus locked at ~71cm; rolling shutter skew 20.5ms (higher than the
  GRL-AL10's 13.7ms — keep sweeps slow on this device too).

## Next steps for this device

1. Capture RAW/DNG bursts (the mode spinner's RAW path has not been
   exercised) — checks `DngCreator` interop, RAW cadence, and stall.
2. Full packages with frame payloads for registration/fusion baselines
   and radiometric verification across all frames.
3. The standard cold-burst series and a sustained batch soak per the
   device-matrix minimum first pass.
