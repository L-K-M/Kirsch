# Kirsch — Full Project Review

**Reviewer:** Claude Opus 5 · **Date:** 2026-07-25 · **Commit reviewed:** `f7529e8`

This is a code-and-design review of the Kirsch Android camera scanner: the
capture controller, sweep policy, imaging pipeline, review flow, and UI. It
covers scanned-document quality, bugs, performance, missing features, layout
and visual issues, and speculative ideas.

Everything here is desk analysis of the source. No physical capture was made,
no device was profiled. Where I estimate a cost (milliseconds, megabytes) it is
an arithmetic estimate from the code, labelled as such — consistent with the
repository's evidence discipline, an estimate is not a measurement.

Findings are tagged:

- **[S1]** serious — user-visible wrong output, data loss, or crash
- **[S2]** significant — quality, performance, or UX cost that a user would notice
- **[S3]** minor — polish, hygiene, or latent risk
- **[IDEA]** speculative / delight

Confidence is stated where it is not obvious.

---

## Table of contents

1. [Scanned document quality](#1-scanned-document-quality)
2. [Bugs](#2-bugs)
3. [Performance and stuttering](#3-performance-and-stuttering)
4. [Layout and visual issues](#4-layout-and-visual-issues)
5. [Missing features](#5-missing-features)
6. [Interface: fast, convenient, appealing](#6-interface-fast-convenient-appealing)
7. [Novel and delightful ideas](#7-novel-and-delightful-ideas)
8. [Documentation drift](#8-documentation-drift)
9. [What I would do first](#9-what-i-would-do-first)

---

## 1. Scanned document quality

### 1.1 [S1] The fused image gets no noise reduction, and gains switching noise

`ConservativeFusion.fuse` picks, for each output pixel, **one source frame's
pixel** — the luma median, or the 20th-percentile sample when a temporal
outlier is detected. The output is therefore a per-pixel patchwork of single
samples.

Two consequences:

1. **No SNR gain from the burst.** A 5-frame stack that averaged its
   agreeing samples would cut read/shot noise by roughly √N ≈ 2.2×. Selecting
   one sample keeps single-frame noise in full.
2. **Selection noise.** Adjacent pixels are drawn from *different* frames.
   Each frame has an independent noise realization and, after registration,
   sub-pixel residual misalignment. Switching source frame pixel-to-pixel
   therefore injects a high-frequency speckle that single-frame capture does
   not have — the fused output can look *noisier* and less sharp than one good
   frame, even where fusion "worked".

The fix that preserves the glare logic exactly: keep the current selection
step, then **average every valid sample whose luma lies within a tolerance of
the selected sample's luma**. Glare samples sit far above that window, so they
are still excluded; agreeing samples are averaged, so noise drops and the
selection map stops switching abruptly. Weighting per channel rather than
copying the winner's BGR bytes also removes the chroma flip described in 1.2.

*Confidence: high on the mechanism; the magnitude of the visible improvement
needs a real capture to state.*

### 1.2 [S2] Selection is by luma but the whole BGR triple is copied

The sort key is `(29·B + 150·G + 77·R) >> 8`. The winner's three bytes are
copied verbatim. If two frames differ in white balance or in local specular
tint (coloured reflections on glossy paper are common), the output inherits
abrupt colour changes at selection boundaries even where luma is continuous.
Averaging the agreeing cluster (1.1) largely fixes this too.

### 1.3 [S1] Rectified output has the wrong aspect ratio

`PrintGeometry.rectify` sizes the output as

```
width  = max(|topLeft→topRight|, |bottomLeft→bottomRight|)
height = max(|topLeft→bottomLeft|, |topRight→bottomRight|)
```

measured in **projected image pixels**. Under perspective, the far edge of a
tilted print is shorter than the near edge, and the ratio of projected edge
lengths is not the ratio of the physical edges. A 10×15 cm print photographed
at a 25–30° tilt comes out visibly stretched or squashed — perhaps 5–12% off,
enough to be obvious next to the physical print, and enough to make the
archival PPI metadata (`ScaleMeasurement.ppiX` vs `ppiY`) inconsistent between
axes for a print the user measured correctly.

This is the most visible remaining geometry defect for a scanner. The standard
remedy is to recover the true aspect ratio from the homography itself (Zhang &
He, *Whiteboard Scanning and Image Enhancement*, 2003): using the four image
corners and the assumption of a square pixel sensor with the principal point at
the image centre, solve for focal length and then for the physical width/height
ratio of the rectangle. It degenerates when the quad is nearly a parallelogram
(near-frontal view) — in that case the current projected-edge estimate is
already correct, so falling back to it is exactly right.

Two secondary wins come free:

- symmetric-axis PPI, so `archival_scale` stops disagreeing with itself;
- an aspect snap: when the recovered ratio is within ~1.5% of a standard print
  (1:1, 4:3, 3:2, 7:5, 16:9 and inverses), snapping to it removes the last
  residual error for the overwhelmingly common case.

### 1.4 [S2] Only 5 of up to 22 swept frames are used, and they are chosen by time

`CaptureFrameLoader.evenlySpacedPositions(count, maximum = 5)` picks five frames
**evenly spaced by index**. Index order is capture time order. But the whole
point of `SweepPolicy` is that the kept frames are spread across *four
directions of displacement* — spatial diversity is the payload, and it is
discarded in favour of temporal spacing.

Worse: `SweepPolicy` computes `positionX/positionY` for every kept frame and
then throws them away. Nothing in the capture package records where each kept
frame sat in the sweep. So the processor could not select for diversity even if
it wanted to.

Two changes, in order:

1. Record each kept frame's accumulated `(positionX, positionY)` (in analysis
   pixels, plus `analysisWidth` for scale) in the frame's capture metadata.
2. Select the processing subset by **maximum displacement spread** (a farthest-
   point / k-centre pass over the recorded positions) rather than by index.

For glare removal specifically, the difference between "five frames from a
20-second sweep, chosen by clock" and "five frames chosen to be maximally
different viewpoints" is the difference between removing the specular core and
not.

### 1.5 [S2] The five-frame cap itself is a memory workaround, not an optimum

More views is strictly better for specular rejection. Five is set by native
memory: at 12 MP, five `CV_8UC3` sources + five aligned + five masks is roughly
`12e6 × 3 × 5 × 2 + 12e6 × 5` ≈ **420 MB** of native allocation at peak
(mitigated somewhat because `BurstRegistration` releases each source as it
goes). Raising the count needs tiled processing — fuse in horizontal bands,
holding only the bands of each aligned frame. That is a real project, not a
patch, but it is the unlock for 8–12 view fusion.

### 1.6 [S2] Registration tolerance is scale-blind

`BurstRegistration` accepts a frame when `meanResidual <= 3.0` pixels. At 12 MP
that is a 3-pixel mean error on a ~4000 px wide image — enough to blur fine
print grain when the fusion then picks between frames pixel-by-pixel. The
threshold should be relative to image width (e.g. `0.0005 × width`, ≈ 2 px at
4000 px, ≈ 0.5 px at 1000 px), not an absolute constant.

Related: a **single global homography** cannot model print curl, page bow, or
rolling-shutter skew during a handheld sweep. Prints are rarely perfectly flat.
A per-tile refinement (ECC or sparse flow residual on a grid) after the global
homography would cut the residual substantially — and would make the higher
frame counts of 1.5 actually pay off.

### 1.7 [S2] Exposure normalization is applied in gamma space

```kotlin
gain = referenceExposure / exposure       // exposure-time × ISO ratio
image.convertTo(it, CvType.CV_8UC3, gain) // applied to 8-bit sRGB
```

Scene radiance ratios are linear; the YUV frames are gamma-encoded. Multiplying
sRGB-encoded values by a linear gain does not reproduce the exposure change — a
2× radiance step is roughly a 1.23× step in 8-bit sRGB code values. Frames with
genuinely different exposure end up tone-mismatched, and the fusion's luma
comparison then mis-ranks them.

In the default sweep path AE is locked, so `gain ≈ 1` and the error is small;
on the `QUALITY_YUV` comparator path on devices without manual sensor control,
where AE can drift, it is not. The correct form is linearize → scale →
re-encode (an sRGB transfer function, or the `x^2.2` approximation).

### 1.8 [S2] Glare and saturation thresholds are absolute, not adaptive

`ConservativeFusion` uses fixed constants: `luma >= 250` means "saturated",
`max − low >= 24` and `median − low >= 10` mean "temporal outlier". These do not
scale with the print's own tone distribution. A dark print with a moderate sheen
(specular luma ~180) never trips the outlier test; a bright, high-key print
tips the failure map red across legitimate highlights. Deriving the thresholds
from per-pixel or per-tile statistics (e.g. a multiple of the local
inter-sample MAD) would make the behaviour consistent across print types.

### 1.9 [S2] Exported images carry no EXIF, no ICC profile, no orientation

`Imgcodecs.imwrite` writes a bare JPEG. The file inserted into the photo
library therefore has:

- no `DateTimeOriginal` — Google Photos and the system gallery sort it by
  insert time, not by when it was scanned, and it lands in the wrong place in
  the timeline;
- no `Make`/`Model`/`Software`;
- no orientation tag;
- no `XResolution`/`YResolution`, even after the user has recorded a confirmed
  physical print size and the app knows the exact PPI;
- no ICC profile, so the sRGB assumption is implicit.

Writing EXIF after `imwrite` (the framework `android.media.ExifInterface` can
edit JPEG in place from API 24) is a small change with a disproportionate
user-visible payoff.

### 1.10 [S3] JPEG chroma subsampling and quality

`IMWRITE_JPEG_QUALITY, 96` with OpenCV's default 4:2:0 chroma subsampling. For
an archival master, 4:4:4 (`IMWRITE_JPEG_SAMPLING_FACTOR` = `4:4:4`) at q=95
costs a few percent in size and preserves the chroma detail that a photo print
scan actually contains. Cheap, strictly better.

### 1.11 [S3] The 16-bit TIFF costs real time and storage for zero information

The README is admirably honest that an 8-bit YUV source in a 16-bit container
is not a 16-bit capture. Given that, writing it by default costs an
`convertTo` over the full image plus a ~40–70 MB encode on every scan. It
should be opt-in (Settings → "Write 16-bit TIFF container"), off by default.

### 1.12 [S3] No final tone/sharpness rendering

There is no unsharp mask, no local contrast, no black/white point placement on
the master. Competing scanners apply a mild capture-sharpening pass because
demosaic + rectification resampling both soften. `INTER_CUBIC` in `rectify` is
reasonable; `INTER_LANCZOS4` is slightly sharper for a near-1:1 warp. A gentle,
*optional* "Auto enhance" (which is what the `ENHANCE` section is for) would
close the gap without touching the master.

### 1.13 [S3] No lens shading or vignetting correction

`STATISTICS_LENS_SHADING_MAP_MODE_ON` is requested and the map is presumably in
the recorded metadata, but nothing consumes it. Prints fill the frame; a 10–15%
corner falloff on a full-frame print is directly visible as darkened edges.

---

## 2. Bugs

### 2.1 [S1] Restorations are created, celebrated, and then never used

`DerivativeStore.createRestoration` writes `restored-<recipe>-xxxxxxxx.jpg`,
appends it to the derivative graph — and **does not update `preview_path`**.

Follow the user:

1. Taps **Fade correction**. Status: *"Saved a new copy: restored-fade-1a2b3c4d.jpg"*.
2. The corner editor still shows the un-enhanced working image (it always
   does — see 2.2), so there is no visual signal either way.
3. Taps **SAVE TO PHOTOS**. `exportToGallery` reads `record.getString("preview_path")`.
4. The photo library receives the **un-enhanced master**.

The enhancement the user asked for, waited for, and was told had succeeded is
silently absent from the only artifact they will ever look at. There is also no
path in the entire UI to view a restored derivative — it exists only on disk
and in the manifest.

By contrast, `createManualRectification` *does* set `preview_path`. The
asymmetry looks like an oversight rather than a decision.

The non-destructive contract is worth keeping — the fix is to make the newly
created derivative the *active* preview while leaving
`derivatives/acquisition-master.jpg` untouched on disk, plus a way to step back
to the master.

### 2.2 [S1] The user never sees what they are about to save

`ReviewActivity` shows `working_image_path` — the **fused, un-rectified** image
— in the corner editor, at up to 1800 px. That is correct for corner editing.
But it is the *only* image the review screen ever displays. The rectified
output, which is the entire deliverable, is never shown. The user taps
**SAVE TO PHOTOS** having never seen the photo.

For a scanner this is the single biggest UX hole. A result view (with a
corners-edit mode you enter deliberately) is the conventional and correct
shape.

### 2.3 [S1] Edge-to-edge is enforced at `targetSdk 35`, and nothing handles insets

`app/build.gradle.kts` sets `targetSdk = 35`. On Android 15, apps targeting
API 35 are **forced** edge-to-edge: `statusBarColor` and `navigationBarColor` in
`styles.xml` are deprecated no-ops, and content draws behind the system bars by
default. No activity calls `setOnApplyWindowInsetsListener`, `fitsSystemWindows`,
or `setDecorFitsSystemWindows`.

Concretely, on any Android 15 device:

- `MainActivity`: the "KIRSCH" title (`topMargin = 18dp`) is drawn under the
  status bar / camera cutout; the controls row (shutter, Scans, Settings) with
  `paddingBottom = 36dp` sits under the gesture bar, so the shutter is partly
  under a system-reserved touch area.
- `ReviewActivity` / `SettingsActivity`: the "Review scan" / "Settings" titles
  render under the status bar clock, and the bottom of the scroll content ends
  under the nav bar.

*Confidence: high — this follows from the documented API 35 behaviour change
plus the absence of any inset code in the tree.*

### 2.4 [S1] A dropped kept-frame silently fails the whole capture

`CapturePackageWriter.finish` accepts only when
`receivedCount == requestedFrameCount && sortedFrames.size == requestedFrameCount`.
For a sweep, `requestedFrameCount` is set to `keptCount` — the number of frames
`SweepPolicy` *decided* to keep.

But between the decision and the write there is `TimestampPairer`, constructed
with `maxPendingImages = 4`. When more than four kept images are waiting for
their `CaptureResult`, the oldest are evicted via `onDropImage = Image::close`
— **silently**, with no warning recorded. `ImageReader` has only
`SWEEP_READER_IMAGES = 6` buffers, and each write is an 18 MB payload plus a
SHA-256 over that payload, so pressure here is not hypothetical.

Result: after a 20-second sweep, `persisted < requested`, `status = "failed"`,
and `MainActivity.onCaptureFinished` shows *"Capture ended with status failed;
the acquisition is retained but not processed"*. The user's whole scan is gone,
and nothing tells them why.

Two fixes, both wanted:

- record a warning whenever the pairer drops a kept image (the manifest already
  has a `warnings` array);
- let the sweep's acceptance be "persisted ≥ `SweepPolicy.minFrames` and
  persisted == what was actually dispatched", recording the shortfall — so a
  slow-IO device degrades to a shorter stack rather than to nothing.

### 2.5 [S2] The confidence map uses the wrong denominator

```kotlin
confidenceRow[column] = ((validCount * 255) / images.size).toByte()
```

`images.size` counts **all** aligned frames, including registration rejects,
which `BurstRegistration.rejected` returns as `Mat.zeros` with an all-zero mask.
Those can never contribute to `validCount`, so the map's ceiling is not 255.

With 5 frames of which 2 were rejected, `validCount ≤ 3` everywhere: confidence
saturates at 153/255 across the whole image, even where all three surviving
frames agreed perfectly. The map is exported as a derivative and is meant to be
the honest record of where fusion worked; right now it systematically
understates it, and the understatement varies with a number the viewer cannot
see.

The denominator should be the number of frames that can actually contribute —
`registration.acceptedFrameCount`.

(The neighbouring `failure` map is *not* affected: `validCount < 3` is an
absolute "too few views agreed to do temporal rejection here" test, which is the
right semantics regardless of how many frames were submitted.)

### 2.6 [S2] Manual re-rectification silently discards recorded print size

`createManualRectification` calls `current.remove("archival_scale")`. That is
defensible — the pixel dimensions changed, so the recorded PPI is stale. But the
user is told nothing: the status line says *"Saved a new copy: …"* and their
carefully measured 102 × 152 mm entry is gone. It should either be re-derived
from the new pixel dimensions (the physical size did not change, only the
sampling) or explicitly reported as cleared.

Re-deriving is almost certainly right: the print's physical width and the
authority are unchanged; only `pixel_width`/`pixel_height` and hence PPI move.

### 2.7 [S2] `ReviewActivity.onCreate` has no error handling at all

```kotlin
manifestFile = File(requireNotNull(intent.getStringExtra(EXTRA_MANIFEST)))
...
val bitmap = requireNotNull(BitmapFactory.decodeFile(working.absolutePath, options))
val quadRecord = manifest.getJSONObject("selected_quad")
```

A missing working image, a truncated manifest, a decode failure on a
low-memory device, or a manifest without `selected_quad` all crash the activity
outright. Given the app writes 25 MB PNGs to external storage and can be killed
mid-write, this is reachable. A failure state ("this scan could not be opened —
reprocess / delete") is needed.

### 2.8 [S2] Failed scans are invisible and unrecoverable from the UI

`ScanProcessor.markFailed` writes `state = "failed"`. Both
`MainActivity.showLibraryDialog` and `SettingsActivity.exportablePackages`
filter to `review`/`accepted`. So a failed scan:

- never appears in the library;
- cannot be retried (nothing re-enqueues it — `ScanQueue.resumePending` treats
  `failed` as complete);
- cannot even be exported for diagnosis;
- but still holds its full acquisition package on disk (see 3.5).

The only trace is a transient status chip. Failed scans should be listed, with
**Retry** and **Delete**.

### 2.9 [S2] Nothing is ever deleted

There is no delete anywhere: not in the library dialog, not in review, not in
settings. Combined with 3.5 this is the practical ceiling on how long anyone
can use the app.

### 2.10 [S3] Status chip never clears

`showStatus` sets `visibility = VISIBLE` and only `startScan()` hides it. An
error, or "Scan ready", stays pinned over the viewfinder indefinitely. It
should fade out after a few seconds (errors excepted).

### 2.11 [S3] Developer strings and filesystem paths in the user-facing chip

`Camera2BurstController` emits all of these to `listener.onStatus`, which
`MainActivity` renders in the chip verbatim:

- `"Waiting for preview 3A convergence"`
- `"Locking exposure, white balance, and focus"`
- `"Camera rejected preview + YUV/I420 stream combination"`
- `"Sweep started"`, `"Saving 7 sweep frames"`
- `"Capture package: /storage/emulated/0/Android/data/ch.lkmc.kirsch/files/captures/capture-20260725t…"`

The last one prints an absolute filesystem path into the UI. All of them are
hardcoded English in Kotlin while everything else lives in `strings.xml`, so the
app is half-localizable. Diagnostics belong in `Log`; the chip should show a
short user-facing message from resources.

### 2.12 [S3] `SweepOverlayView` is invisible to screen readers

The framing hint, the sweep hint, the ring, and the percentage are all
`Canvas.drawText`/`drawArc`. TalkBack sees an unlabeled `View`. There is no
`contentDescription`, no `announceForAccessibility` on progress, no
`AccessibilityNodeInfo`. A blind or low-vision user cannot perform the sweep —
which is the app's entire capture interaction. The status chip is a polite live
region, but the chip is hidden during sweeping.

### 2.13 [S3] `showLibraryDialog` parses every manifest twice on the UI thread

`listFiles` → `readText` + `JSONObject` in the filter, then `readText` +
`JSONObject` again in the label map, both on the main thread, both without
`runCatching` in the second pass (so a file that changes between passes
crashes). With a few dozen scans on external storage this is a visible hitch on
tapping **Scans**.

### 2.14 [S3] Bitmap and Mat lifecycle leaks

- `CornerEditorView` never recycles its bitmap in `onDetachedFromWindow`; each
  `loadScan()` decodes a fresh ~1800 px bitmap (≈13 MB at ARGB_8888) and
  recycles the previous one, but the last one leaks with the view.
- `BurstRegistration.register` throws from `require(referenceDescriptors.rows() >= 12)`
  before releasing `referenceKeypoints`, `referenceDescriptors`, and the ORB
  instance. Native, so no `OutOfMemoryError` — just retained memory until
  finalization.
- `orb.detectAndCompute(gray, Mat(), …)` allocates a throwaway mask `Mat` per
  call, never released.
- `ScanProcessor` releases `frames.forEach { it.bgr.release() }` on Mats that
  `BurstRegistration` already released. Harmless (OpenCV `release()` is
  idempotent) but it signals unclear ownership.

### 2.15 [S3] `ScanQueue` retains dead activities

The listener passed to `enqueue` is captured by the executor task. If
`MainActivity` is destroyed while a scan runs, the task keeps the destroyed
activity alive until processing finishes (tens of seconds — see 3.1). Each
`onResume` re-registers the current instance via `resumePending`, so instances
can stack. Bounded, but it should be a weak reference or an application-scoped
observable.

### 2.16 [S3] `pendingReviewScanId` does not survive process death

It is a plain field, never written to `onSaveInstanceState`. Process death
between capture and processing loses the automatic review hand-off;
`resumePending` re-processes but does not re-open review. Minor, and the
existing `onResume` recovery path already covers the common case.

### 2.17 [S3] `onResume` skips camera start and queue resume when handing off

```kotlin
if (ready) { pendingReviewScanId = null; startActivity(...); return }
startCameraIfReady()
ScanQueue.resumePending(this, this)
```

The early `return` means neither runs on that pass. Correct in effect (we are
navigating away and `onResume` fires again on return), but it makes the camera
lifecycle depend on an unrelated branch.

### 2.18 [S3] `configureTransform` assumes a 90°/270° sensor

It forces `contentWidth = min(previewW, previewH)`, i.e. it hard-codes that the
buffer delivered to the `TextureView` is portrait-shaped. That holds for the
overwhelmingly common `SENSOR_ORIENTATION` of 90 or 270 in a portrait-locked
activity, but a device reporting 0 or 180 would show a wrongly-stretched
preview. Reading `SENSOR_ORIENTATION` and branching costs two lines.

---

## 3. Performance and stuttering

### 3.1 [S1] Processing is a 20–40 second black box with no progress

Order-of-magnitude estimate for one 5-frame, 12 MP scan on a mid-range phone:

| Stage | Estimate |
|---|---|
| SHA-256 verification of 5 × 18 MB payloads | 2–4 s |
| I420 → BGR decode ×5 | 1–2 s |
| ORB (8000 features) detect+compute on 12 MP ×5 | 5–12 s |
| kNN match + MAGSAC++ ×4 | 1–3 s |
| `warpPerspective` 12 MP ×4, plus mask warps | 1–2 s |
| `ConservativeFusion` — 60 M Java inner-loop iterations | 2–6 s |
| `fused.png` — full-res PNG encode | 3–6 s |
| rectify + JPEG + **16-bit TIFF** + 2 PNG maps | 4–8 s |
| **Total** | **≈ 20–45 s** |

During all of it the user sees one static chip: *"Photo captured — processing…"*.
No spinner, no stage, no percentage, no cancel. They can start another scan,
which queues behind it on the same single-thread executor and makes the wait
longer with no indication that it did.

The cheapest large win is staged progress reporting (`ScanQueue.Listener` gains
an `onScanProgress(stage, fraction)`); the cheapest large *speed* win is 3.2 and
3.3.

### 3.2 [S2] ORB runs at full 12 MP

Feature detection and description at 12 MP is where most of the registration
time goes, and it buys very little: a homography from ORB+MAGSAC++ estimated on
a 1600 px-wide downscale, then scaled up (`H' = S⁻¹ H S`), is essentially as
accurate for a global planar transform and is roughly **10–20× cheaper**. The
warps still run at full resolution. This is a standard, safe optimization.

### 3.3 [S2] `working/fused.png` is a full-resolution PNG

PNG encoding 12 MP of BGR is seconds of CPU and ~20–30 MB on disk, per scan.
The file exists only so manual corner correction can re-rectify from the
unrectified fused image. A q=98 JPEG (~3 MB, <1 s) or lossless WebP would serve
that purpose; the *master* stays as it is. The 16-bit TIFF (1.11) is the other
half of this cost.

### 3.4 [S2] The sweep runs a full-resolution stream at full frame rate

During the sweep the repeating request targets **both** the preview surface and
the 12 MP `ImageReader`. For up to 20 seconds the HAL produces full-resolution
YUV continuously — hundreds of 18 MB frames, of which the policy keeps at most
22. Everything else is analyzed and immediately closed.

Costs: sustained ISP load and thermal throttling (the manifest already records
thermal status, which suggests this was anticipated), `6 × 18 MB` = ~108 MB of
graphics buffers held, and a capture rate limited by full-resolution readout
rather than by the analysis, so the ring updates at maybe 10–15 Hz instead of
30.

The structurally right design is a small analysis stream (e.g. 640×480 YUV) for
motion measurement, with a full-resolution still requested **only** for the
frames the policy decides to keep. The caveat is honest: three concurrent
streams (PRIV preview + YUV analysis + YUV maximum) are not in the guaranteed
`StreamConfigurationMap` combinations for all hardware levels, so this needs a
capability check with a fallback to the current single-stream behaviour, and it
needs device testing. It is a project, not a patch — but it is the single
largest power and thermal win available.

### 3.5 [S1] Storage: roughly half a gigabyte per scan, never reclaimed

| Artifact | Size |
|---|---|
| 22 × I420 at 12 MP (18 MB each) | **≈ 396 MB** |
| `working/fused.png` | ≈ 25 MB |
| `acquisition-master.tif` (16-bit, 3ch) | ≈ 40–70 MB |
| master JPEG + confidence + failure maps | ≈ 6 MB |
| **per scan** | **≈ 470–500 MB** |

Nothing deletes anything, ever. Twenty scans is ~10 GB. There is no storage
indicator, no retention policy, no "delete sources after accept" option, and no
delete action in the UI (2.9).

The acquisition packages are retained deliberately — that is the evidence
contract, and it is right for benchmark sessions. But for the product path it
needs at minimum: a visible storage figure in Settings, per-scan delete, and an
opt-in "discard acquisition after the scan is accepted" that preserves the
manifest and hashes while dropping the payloads.

### 3.6 [S2] `Yuv420Packer.copyPlane` copies 18 M bytes one `ByteBuffer.get(int)` at a time

```kotlin
for (row in 0 until height)
  for (column in 0 until width)
    output[outputIndex++] = buffer.get(base + (startY+row)*rowStride + (startX+column)*pixelStride)
```

12 MP luma + chroma is ~18 million individually bounds-checked `get(int)` calls
per frame, plus an index recomputation each iteration. When `pixelStride == 1`
— the case for the Y plane on essentially every device, and for chroma on
planar devices — the entire row is contiguous and can be taken with a single
bulk `ByteBuffer.get(byte[], offset, length)`. That is typically **5–20×**
faster for those rows.

This runs on the capture IO path, once per kept frame, up to 22 times per sweep,
while the camera is still streaming and buffers are scarce (2.4). It is a
contained, unit-testable change with no behavioural difference.

### 3.7 [S2] SHA-256 over every payload, twice

`CapturePackageWriter.fileRecord` hashes each 18 MB payload as it is written;
`CaptureFrameLoader.verifyFile` hashes it again at processing time. That is
~660 MB of hashing on write and ~180 MB on read, per scan. The integrity
contract is worth keeping, but the write-side hash can be computed
incrementally in the same pass that writes the bytes (`DigestOutputStream`)
instead of re-reading the file from external storage.

### 3.8 [S3] `TimestampPairer(maxPendingImages = 4)` against a 6-buffer reader

Four pending images plus in-flight writes leaves almost no slack in a
six-buffer `ImageReader`. When it runs dry the camera pipeline stalls, which
shows up as a preview freeze mid-sweep — exactly when the user is being asked
to move smoothly. See also 2.4 for the correctness half of this.

### 3.9 [S3] `ConservativeFusion` inner loop

60 M iterations of Java array access plus a per-pixel insertion sort. It is
correct and allocation-free, which is good, but it is also the kind of loop that
belongs in a small JNI/OpenCV vectorized form (e.g. `Core.merge` on per-channel
`Core.min`/percentile stacks) or at minimum row-parallelized across cores. Four
threads on row bands would cut it ~3.5×, and the loop is trivially independent
per row.

---

## 4. Layout and visual issues

### 4.1 [S1] No window inset handling — see 2.3

The top title and bottom controls collide with the system bars on Android 15.
This is the most visible layout defect.

### 4.2 [S2] The UI is `Theme.Material.NoActionBar` with hand-built views

`android.useAndroidX=false`, so there is no Material Components, no
`MaterialButton`, no `ConstraintLayout`. Every screen is `LinearLayout` +
`TextView` + platform `Button` built in Kotlin. The consequences are visible:

- **Buttons.** `APPLY CORNERS`, `SAVE TO PHOTOS`, the four enhance buttons and
  `PRINT SIZE…` all render as stock Material-1 grey raised buttons from ~2014,
  inside an otherwise carefully art-directed dark amber theme. The mismatch is
  jarring.
- **Spinners.** `SettingsActivity`'s capture-profile spinner and the review
  dialog's authority spinner use `android.R.layout.simple_spinner_dropdown_item`
  — black-on-white text dropped into a `#0E0D0B` screen.
- **Dialogs.** `AlertDialog.Builder(this)` with the platform theme is a light
  dialog over a dark app. Library, export, capabilities and print-size dialogs
  all flash white.
- **EditText.** The print-size fields inherit default styling with no hint
  colour set (the settings one sets it, the review one does not), so hints may
  be near-invisible on dark.

None of this needs AndroidX: styled `GradientDrawable` backgrounds and a
`Theme.Material` dark parent (`android:Theme.Material.NoActionBar` is already
"Material dark" only in name — the *default* `Theme.Material` is dark, but
dialogs and spinners still need explicit themed attributes) fix it. Adopting
AndroidX + Material 3 would fix it properly and unlock much else, at the cost
of a large diff.

### 4.3 [S2] The review screen has no navigation and no structure

`ReviewActivity` is a single `ScrollView` with a text title. No back arrow, no
app bar, no close. The only way out is the system back gesture. After saving,
there is no "Done" or "Scan another" — the user is left on a locked screen.

### 4.4 [S2] The corner editor is small, un-zoomable, and mixed into a scroll view

`CornerEditorView` has `minimumHeight = 280dp` and sits inline in a
`ScrollView`. Precise corner placement on a ~350 dp-tall image of a 4000 px
print means each screen pixel is ~10 image pixels — the magnifier helps, but
there is no pinch-zoom and no pan. It also fights the `ScrollView` for
vertical drags; `requestDisallowInterceptTouchEvent(true)` handles this once a
handle is grabbed, but a drag that starts >48 dp from a handle scrolls the page
instead, which reads as "the editor ignored me".

A dedicated full-screen crop step with pinch-zoom is the right shape.

### 4.5 [S3] The framing brackets are decorative

`drawFraming` draws corner brackets at fixed 12% / 18% insets, unrelated to
anything detected. They imply "put the photo inside this box", but the box does
not match any print aspect and nothing checks it. That is a deliberate
constraint (no detection-driven overlay — see `PLAN.md` §7), and it should stay,
but the *fixed* box could at least follow the preview's aspect ratio and the
hint could say what it means.

### 4.6 [S3] The sweep percentage can look stuck

`bestProgress = max(bestProgress, min(coverage, frameCount/minFrames))` is
monotonic and honest, but it means the number can freeze for seconds while the
user is moving — because motion in an already-satisfied direction adds nothing.
The four ring segments do convey this, but the big central number is what the
eye goes to. Either drop the percentage in favour of the ring, or annotate it.

### 4.7 [S3] The library dialog shows raw capture IDs

*"capture-20260725t143052117z-a1b2c3d4 · accepted"*. No thumbnails, no
human-readable date, no grid. A photo app's library should be a grid of
thumbnails.

### 4.8 [S3] No landscape, no tablet, no large-screen consideration

`MainActivity` and `ReviewActivity` are `screenOrientation="portrait"`;
`SettingsActivity` is not, so it is the only rotatable screen. Portrait-locked
capture is defensible for this interaction; a portrait-locked *review* screen
for a landscape print is not — the print is displayed at half the available
width for no reason.

### 4.9 [S3] No `values-night`, no other locales, hardcoded colours

Colours are `0xFFF3EDE2.toInt()` literals scattered through five Kotlin files
rather than `colors.xml`. `colors.xml` contains exactly one entry (the launcher
background). Changing the accent means editing six files.

---

## 5. Missing features

Ordered by how often a user would want them.

### 5.1 [S1] Share

There is no share action anywhere. A scanner whose output cannot be sent to
anyone is doing half the job. Requires a `FileProvider` (no network permission
needed) and an `ACTION_SEND` chooser. The absence is conspicuous next to the
carefully-built MediaStore export.

### 5.2 [S1] Delete / retry

See 2.8 and 2.9. Delete a scan, delete an acquisition, retry a failed scan.

### 5.3 [S2] PDF export and multi-page

For "document scanner" framing, PDF is table stakes: several scans → one PDF.
`android.graphics.pdf.PdfDocument` is in the framework, no dependency needed.

### 5.4 [S2] Rotate

If `PrintGeometry.order()` picks the wrong starting corner, or the print was
placed sideways, the output is rotated 90° and the user cannot fix it. A
four-way rotate in review is a ten-line feature and a constant real-world need.

### 5.5 [S2] Batch / continuous scanning

Every scan forces a round trip through review. Scanning a shoebox of 200 photos
means 200 review screens. A "keep scanning" mode that queues scans and reviews
them in a batch afterwards is the difference between a demo and a tool.

### 5.6 [S2] Tap-to-focus and exposure compensation

Standard camera affordances, entirely absent. The user cannot tell the camera
where the print is. Note this is *not* the patent-sensitive guidance the plan
warns about — it is user-initiated AF metering, not glare-driven or
corner-targeted capture guidance.

### 5.7 [S2] First-run coaching for the sweep

The sweep is an unusual interaction. The user taps a shutter and then, instead
of getting a photo, is told to "move the phone in slow circles". Without a
one-time animated explanation of *why* (four viewpoints let the app see past
reflections) and *how far*, the likely first-run behaviour is a small wiggle,
a 20-second timeout, an `endedEarly` warning nobody sees, and a disappointing
result.

### 5.8 [S2] Auto-crop confidence feedback

`ScanProcessor` writes all detected quads into `processing-report.json` and the
review screen uses only the first. The user cannot pick a different detected
region, and gets no signal about whether detection succeeded or fell back to
`fullFrame`. "We couldn't find the print edges — drag the corners" is important
information that the app has and does not surface.

### 5.9 [S3] Torch / low-light warning

No torch control and no "too dark" indication. The sweep's sharpness gate will
quietly reject frames in dim light and the sweep will time out, with no
explanation.

### 5.10 [S3] Level / tilt indicator

Shooting square-on materially improves geometry and reduces the aspect error of
1.3. The accelerometer is free. A subtle bubble level (device-attitude driven,
not image-driven, so it stays clear of the guidance constraints) is a real
quality lever.

### 5.11 [S3] Print-size presets

The archival dialog asks for millimetres as free text. 10×15, 9×13, 13×18,
20×25, square Polaroid, and "custom" covers ~95% of consumer prints and turns a
typing task into a tap. It could also feed an aspect-snap for the crop.

### 5.12 [S3] Scan naming and search

Scans are identified by `capture-<timestamp>-<uuid8>` forever. No title, no
tags, no album, no notes, no back-of-photo capture (which the capability list
explicitly gates on a missing HTR model — but a *photo* of the back, stored
alongside, needs no model at all and is what most people actually want).

### 5.13 [S3] Storage insight

Settings shows nothing about the ~500 MB per scan (3.5).

---

## 6. Interface: fast, convenient, appealing

Summarising the interaction as it stands:

**What is good.** The capture screen is clean and confident — full-bleed
preview, one obvious shutter, two pill buttons, a good dark amber palette. The
four-segment ring is a genuinely well-chosen metaphor (everyone has enrolled a
fingerprint). The corner editor's magnifier and 48 dp grab radius are
thoughtful details. Accessibility live regions on the status text show someone
was paying attention. The honesty discipline — `endedEarly`, `used_fusion`,
`scan_locked` vs `scan_accepted`, "no delivered-resolution claim" — is unusual
and worth preserving exactly as it is.

**What breaks the experience.**

1. **The review screen is a form, not a photo.** It shows an un-rectified
   working image, a wall of `ALL-CAPS` section headers, seven grey buttons and
   five caption paragraphs. The user came to see their photo. (2.2, 4.3)
2. **Nothing indicates progress** for 20–45 seconds of processing. (3.1)
3. **Enhancements do nothing visible and are not saved.** (2.1)
4. **The system bars overlap the controls.** (2.3)
5. **The copy is engineering prose.** *"Applies the corner correction and
   updates the scan"*, *"Recorded in capture packages so benchmark scans can be
   traced to a physical print"*, *"Sampling frequency recorded: 302.3 × 301.8
   PPI. No delivered-resolution claim was made."* The rigour is admirable and
   should stay in the manifests and the reports; the *chrome* should speak to a
   person holding a shoebox of photos.
6. **No haptics, no sound, no motion.** The ring fills, the sweep completes, the
   scan saves — all in total silence with no animation. The fingerprint metaphor
   the ring borrows is 90% haptic.

**Speed.** Time from "tap shutter" to "usable photo in the library" is roughly
20 s of sweep + 20–45 s of processing + review interaction ≈ **a minute per
photo**, versus ~3 seconds for a competing app's single-shot mode. The glare
removal justifies some of that, but 3.2/3.3 alone could halve the processing
half, and 3.4 would make the sweep itself lighter and faster.

---

## 7. Novel and delightful ideas

### 7.1 The glare reveal

The app's whole reason to exist is invisible. After processing, show a
**before/after slider**: the best single frame on one side, the fused result on
the other, with the reflection sliding away under the user's thumb. It uses
data already on disk (`working/fused.png` plus any source frame), it is the most
persuasive thing this app could possibly show, and it turns an abstract
20-second sweep into a visible payoff. If the fusion did not help, the slider
shows that honestly too — which fits this project's temperament exactly.

### 7.2 A motion trail instead of a percentage

`SweepPolicy` already tracks `(positionX, positionY)` — the camera's path
through displacement space. Draw it: a fading comet trail of where the phone has
been, with the four direction targets as faint arcs it needs to reach. It is
strictly more informative than "43%", it is beautiful, and it is *purely
motion-derived*, so it stays inside the design constraint that guidance never
reacts to image content or print position.

### 7.3 Haptics, borrowed properly

One crisp `HapticFeedbackConstants.CONFIRM` tick as each of the four ring
segments completes, and a double-pulse on completion. That is the entire reason
fingerprint enrollment feels good. Roughly six lines of code.

### 7.4 Cherry

The app is named after cherry brandy and the icon is a cherry on a scanner, and
then nothing in the app ever mentions it again. The empty library says *"No
scans yet — scan your first photo"*. It could say something with a cherry in it.
The completion animation could be a cherry. The distillery metaphor is right
there: captures are the *mash*, the fused master is the *distillate*,
derivatives are *casks*. This is a personal project — it is allowed to have a
personality.

### 7.5 Shoebox mode

Point the camera at a photo, sweep, and instead of going to review, it snaps
back to the viewfinder with a small thumbnail sliding into a stack in the
corner. Scan thirty photos in ten minutes; review the stack afterwards over
coffee. Pair with a running counter and a gentle "23 scanned · 2 need a look".

### 7.6 The back of the photo

Handwriting *recognition* is correctly gated on a missing model. But a photo of
the back, stored as a sibling derivative and shown behind a flip animation in
review, needs no model at all — and the writing on the back is very often the
most valuable thing about an old print. A literal card-flip gesture on the
result view would be delightful and genuinely useful.

### 7.7 Print-size aspect snapping with a physical rationale

When the recovered aspect ratio (1.3) lands within ~1.5% of a standard print
size, snap to it and say so quietly: *"Looks like a 10 × 15 — snapped."* Users
love a machine that recognises a physical object correctly, and it makes the
geometry exactly right rather than approximately right.

### 7.8 A scan's own provenance page

The manifest already contains a full derivative graph with hashes, parents,
recipes, timestamps, thermal state and processing latency. Nobody ever sees it.
A tasteful "how this scan was made" sheet — 7 frames kept from a 14-second
sweep, 5 registered, fusion accepted, 302 PPI from confirmed dimensions — is
both a debugging tool and, for an archival tool, quietly reassuring.

### 7.9 Live level with a satisfying snap

A faint horizon line that turns amber and gives one tick when the phone is
within 2° of parallel to the print (from the accelerometer, not the image).
Improves geometry, costs nothing, feels like a real instrument.

### 7.10 Time-machine sort

Scans have no dates from the *photo*, only from the scan. But most prints have a
lab date code printed on the back or a border stamp on the front. Even without
recognising it, letting the user tag a scan with an approximate year and then
seeing the library sorted as a timeline would turn a pile of files into a family
archive.

---

## 8. Documentation drift

- `PHASES1-3.md` says the sweep keeps "minimum five frames, maximum **twelve**".
  `CaptureProfile.SWEEP.frameCount` and `SweepPolicy.Settings.maxFrames` are
  both **22**, and `app/README.md` says twenty-two. The phases record is stale.
- `app/README.md` says processing "selects up to five evenly spaced frames from
  the acquisition to bound native memory (sweep keeps are already
  displacement-spaced)". The parenthetical is not true in a useful sense —
  they are displacement-spaced *from each other in time*, and the selection then
  ignores displacement entirely (1.4).
- `Camera2BurstController.start(mode: CaptureMode)` is dead code — nothing calls
  the `CaptureMode` overload; `MainActivity` always passes a `CaptureProfile`.
- `ScanState.PREVIEW/CONVERGING/CAPTURING/PERSISTING` exist in the state machine
  and its transition table but no code ever transitions through them —
  `ScanProcessor` starts at `QUEUED`. The capture-side states are aspirational.

---

## 9. What I would do first

If I had to rank the whole list by (user-visible improvement ÷ risk):

1. **2.1** — restorations become the active output. A user is currently told an
   enhancement was applied and then given the file without it.
2. **2.2** — show the rectified result in review.
3. **1.3** — aspect-correct rectification. Every single scan is affected.
4. **2.3** — window insets. Every screen, every Android 15 device.
5. **1.1** — average the agreeing samples. The core image-quality lever.
6. **3.5 / 2.9** — delete, and a retention policy. The app is currently
   unusable past ~20 scans on a 128 GB phone.
7. **3.1** — processing progress. Nothing changes but the perception, and the
   perception is currently "it hung".
8. **2.4** — do not throw away a 20-second sweep because one buffer was
   evicted.
9. **2.5** — confidence and failure maps that mean what they say.
10. **3.6 / 3.2 / 3.3** — the three contained speed fixes.

Then the features: share, rotate, delete, batch mode, tap-to-focus, coaching.
Then, if the appetite exists, the two structural projects: the low-resolution
sweep analysis stream (3.4) and tiled fusion over more views (1.5).
