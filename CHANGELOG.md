# Changelog

## Unreleased

- Keep Save to Photos visible outside scrolling review controls and clear of system bars, including after corner correction and with large text.
- Detect low-contrast and rounded print boundaries using color edges and supported straight sides.
- Retain the sharpest recorded sweep view and preserve reference detail unless multiple views and spatial support justify glare replacement.
- Improve sweep focus, shutter choice, blur rejection, and viewpoint selection.
- Select a 10 ms sweep shutter target for detected 50 Hz lighting; retain 8.333 ms otherwise.
- Preserve rendered color and detail with safer registration and fusion.
- Correct calibrated print shape at single-axis tilt and preserve full-frame pixels.
- Normalize crop and calibration origins, select the basis from actual distortion mode, and decline ambiguous camera geometry.
- Show and zoom the finished scan, preserve crop drafts, and add reversible rotation.
- Keep captured scans upright through rectification, review, editing, and export.
- Keep live edits and saves consistent across review recreation and resumption.
- Keep older scan completion from interrupting a newer capture.
- Report incomplete camera calibration as validation errors instead of crashing.
- Offer failed-scan retry and guard editing and photo-library publication.
- Keep accepted orientation and physical scale consistent with the saved version.
- Run native OpenCV and review-flow regressions on an Android emulator in CI.

One user-provided glossy-card acquisition was privately replayed and inspected
at native resolution, reproducing failed cropping and fusion artifacts before
correction. Matched comparisons against the stock camera and PhotoScan remain
unverified; regression tests do not establish that result.
