# Changelog

## Unreleased

- Improve sweep focus, shutter choice, blur rejection, and viewpoint selection.
- Preserve rendered color and detail with safer registration and fusion.
- Correct calibrated print shape at single-axis tilt and preserve full-frame pixels.
- Show and zoom the finished scan, preserve crop drafts, and add reversible rotation.
- Keep live edits and saves consistent across review recreation and resumption.
- Report incomplete camera calibration as validation errors instead of crashing.
- Offer failed-scan retry and guard editing and photo-library publication.
- Keep accepted orientation and physical scale consistent with the saved version.
- Run native OpenCV and review-flow regressions on an Android emulator in CI.

Physical glossy-print comparisons against the stock camera and PhotoScan remain
unverified; synthetic tests and successful builds do not establish that result.
