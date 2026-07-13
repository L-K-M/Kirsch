# Camera2 Burst Prototype

This app is an evidence-gathering tool, not the Phase 1 scanner UI. It opens a rear camera through direct Camera2, configures preview plus one capture stream, locks the available 3A controls, submits five non-interleaved requests, and saves timestamp-matched results.

## Outputs

App-private external storage contains:

```text
Android/data/com.example.kirsch/files/captures/
  capture-<UTC>-<suffix>/
    capture.json
    camera-characteristics.json
    frames/
      frame-00.dng              # RAW mode
      frame-00.i420             # YUV mode, canonical Y/U/V planar bytes
      frame-00.json             # CaptureResult and delivery metadata
```

`capture.json` follows `benchmark/schema/capture-package-v0.1.schema.json`. Copy a package from the device and run:

```bash
python3 benchmark/tools/kirsch_benchmark.py validate-capture /path/to/capture.json
```

YUV output is lossless relative to the delivered 8-bit `YUV_420_888` samples. It is not sensor RAW. The sidecar records source row/pixel strides and crop geometry; the `.i420` payload itself is tightly packed Y, then U, then V.

The manifest reports `received_frame_count` when an image/result pair reaches the writer and `persisted_frame_count` only after payload and sidecar writes succeed. Accepted packages require both counts, the frame list, and the requested count to match with no errors.

## Hardware Procedure

1. Install the debug APK and grant camera permission.
2. Enter the assigned benchmark print ID.
3. Select RAW or YUV. RAW automatically falls back to YUV if the chosen rear camera lacks `RAW_SENSOR`.
4. Wait for preview metadata, then capture one burst.
5. Keep the app foregrounded until all five files are written.
6. Copy the package, validate it, and record the package as a benchmark acquisition.

The status panel reports the selected camera, hardware level, RAW/manual/burst capabilities, sizes, output stall/min-frame duration, 3A-lock degradation, and output directory.

## Known Phase-0 Limits

- Stream combinations and timestamp behavior still require real-device qualification.
- The prototype uses preview plus one capture reader; RAW and YUV require session reconfiguration.
- ARCore coexistence, CameraX sequential RAW fallback, energy instrumentation, and thermal-soak orchestration are not implemented yet.
- App-private external storage is intentional. There is no background upload or cloud path.
- A process death during a burst can leave a partial directory without `capture.json`; ingestion must reject it.
