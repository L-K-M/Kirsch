# Kirsch — Analysis and Open Work

**Origin:** full desk review of commit `f7529e8`, 2026-07-25.
**Status:** ten findings addressed in PRs #22–#27; the rest is open work.

This is the living backlog. Items that shipped are recorded in
[§1](#1-landed) with what changed and where, so nothing is lost; everything
else is the work ahead, ordered so it can be picked up without re-deriving the
analysis.

The review was desk analysis of the source. No physical capture was made and no
device was profiled. **Every cost figure below (milliseconds, megabytes,
percentages) is an arithmetic estimate from the code or from synthetic
geometry, not a measurement**, unless it is explicitly labelled otherwise —
consistent with the evidence discipline in `PHASE0.md`. A green build is not
image-quality evidence.

Findings are tagged **[S1]** serious (user-visible wrong output, data loss,
crash), **[S2]** significant (quality, performance or UX cost a user notices),
**[S3]** minor (polish, hygiene, latent risk), **[IDEA]** speculative.

---

## Table of contents

1. [Landed](#1-landed)
2. [Scanned document quality](#2-scanned-document-quality)
3. [Bugs](#3-bugs)
4. [Performance and stuttering](#4-performance-and-stuttering)
5. [Layout and visual issues](#5-layout-and-visual-issues)
6. [Missing features](#6-missing-features)
7. [Interface assessment](#7-interface-assessment)
8. [Ideas](#8-ideas)
9. [Documentation drift](#9-documentation-drift)
10. [Ranked next steps](#10-ranked-next-steps)

---

## 1. Landed

| PR | What it fixes |
|---|---|
| [#22](https://github.com/L-K-M/Kirsch/pull/22) | Fusion selected one source pixel per output pixel, so the burst gave no noise reduction and switching between frames added a speckle no input frame had. The glare decision is unchanged; the samples that agree with it are now averaged. Also fixed the confidence map dividing by `images.size` including registration rejects, which made its ceiling unreachable. |
| [#23](https://github.com/L-K-M/Kirsch/pull/23) | Rectified output was sized from projected edge lengths, stretching every off-axis scan — 6.4% at a 25° tilt, 21% for a 4×6 at 30°, against synthetic projections. Now sized from the aspect ratio recovered from the four corners, at constant pixel count, with fallback when the solve degenerates. |
| [#24](https://github.com/L-K-M/Kirsch/pull/24) | A restoration was created, reported as saved, and then never used — **Save to Photos** exported the un-enhanced master. Restorations now become the active output, with **Use original scan** to go back. Also stopped manual rectification silently discarding recorded print size. |
| [#25](https://github.com/L-K-M/Kirsch/pull/25) | `targetSdk 35` forces edge-to-edge on Android 15 and nothing handled insets, so the title drew under the status bar and the shutter sat under the gesture bar. All three screens now inset for system bars, cutout, and IME. |
| [#26](https://github.com/L-K-M/Kirsch/pull/26) | `Yuv420Packer.copyPlane` read ~18 M samples one indexed `ByteBuffer.get` at a time per frame, on the capture IO path, up to 22 times per sweep. Contiguous rows now bulk-copy. |
| [#27](https://github.com/L-K-M/Kirsch/pull/27) | A kept sweep view evicted from the pairer before its `CaptureResult` was closed silently, stranding the target count and failing the entire capture after a 20-second sweep. Losses are now recorded and the deliverable count follows them down. |

### Open PRs from earlier sessions that overlap this backlog

Three PRs from a 2026-07-16 session (against the same base, `f7529e8`) were
still open when this review was written and cover ground it also touches. They
came out of an earlier project review, issue #13.

| PR | Overlaps |
|---|---|
| [#19](https://github.com/L-K-M/Kirsch/pull/19) | Moves scan loading, the library listing, and `ScanQueue.resumePending` off the main thread — [§3.7](#37-s3-showlibrarydialog-parses-every-manifest-twice-on-the-ui-thread), [§3.9](#39-s3-scanqueue-retains-dead-activities), and part of [§3.3](#33-s2-reviewactivityoncreate-has-no-error-handling-at-all). |
| [#20](https://github.com/L-K-M/Kirsch/pull/20) | **Same bug as #24**, solved differently, plus export EXIF — [§2.7](#27-s2-exported-images-carry-no-exif-no-icc-profile-no-orientation). |
| [#21](https://github.com/L-K-M/Kirsch/pull/21) | Animates the coverage ring and moves the package path out of the status chip — [§3.5](#35-s3-developer-strings-and-filesystem-paths-in-the-user-facing-chip), [§5.5](#55-s3-the-sweep-percentage-can-look-stuck). |

**#20 and #24 conflict, and not only textually.** Both fix "a restoration is
created, reported as saved, and then not what gets exported", and they disagree
about the remedy:

- **#20** keeps `preview_path` on the master and asks *at save time* which
  version to export, recording the choice as `gallery_source_path`.
- **#24** makes the restoration the active output as it is created, so the
  review screen and the export agree by construction, with **Use original
  scan** to step back.

One of them should merge, not both. #20 additionally carries the EXIF work of
§2.7, which #24 does not; whichever way the version question is settled, that
part is wanted either way.

#19 also overlaps #24 lightly (both touch `ReviewActivity`'s reload path) and
its own note predicts a textual conflict with #20.

### Verification still owed on landed work

- **#25** is layout behaviour: build and lint are green but it has not been seen
  on a device with a gesture bar and a cutout.
- **#27** runs inside the camera handler against a live session; it needs a
  sweep on a slow-storage device, checking the capture is accepted with a
  warning and a reduced `requested_frame_count` rather than failing.
- **#22** and **#26** claim no measured improvement. The noise and throughput
  arguments follow from arithmetic; the visible effect on real glossy prints is
  the next capture round's question.

---

## 2. Scanned document quality

### 2.1 [S2] Only 5 of up to 22 swept frames are used, and they are chosen by clock

`CaptureFrameLoader.evenlySpacedPositions(count, maximum = 5)` picks frames
**evenly spaced by index**, and index order is capture order. But the entire
point of `SweepPolicy` is that kept frames are spread across *four directions of
displacement* — spatial diversity is the payload, and it is discarded in favour
of temporal spacing.

`SweepPolicy` computes `positionX`/`positionY` for every kept frame and then
throws them away. Nothing in the capture package records where a kept frame sat
in the sweep, so the processor could not select for diversity even if it wanted
to.

Two changes, in order:

1. Record each kept frame's accumulated `(positionX, positionY)` in analysis
   pixels, plus `analysisWidth` for scale, in the frame's capture metadata.
2. Select the processing subset by **maximum displacement spread** — a
   farthest-point pass over the recorded positions — instead of by index.

For glare removal this is the difference between five frames chosen by clock and
five chosen to be maximally different viewpoints.

### 2.2 [S2] Single-axis tilt is the common pose and still gets a wrong aspect

Surfaced while implementing #23, and pinned by a test there rather than fixed.

A phone held level side to side but tipped forward over a print keeps one pair
of edges parallel in the image. Only one vanishing point is finite, no focal
length can be solved for, and `aspectRatio` returns null — so the output falls
back to projected edges. **A 3:2 print at 35° of pure pitch comes out about 30%
too wide.** That is worse than any case #23 fixed, and it is arguably the most
common scanning pose there is. (Two degrees of second-axis tilt is enough for
the solve to recover exactly, so in practice the error is bimodal.)

The fix needs the camera's focal length in pixels, which makes it concrete:

- `CaptureMetadata` already records `lens_focal_length_mm` per frame and
  `sensor_pixel_array`, but **not** `SENSOR_INFO_PHYSICAL_SIZE`, so mm cannot be
  converted to pixels. Add it.
- Thread intrinsics from the capture package through `CaptureFrameLoader` into
  `ScanProcessor` and down to `PrintGeometry`.
- `normalizedLengthSquared` already takes `squaredFocal` as a parameter, so the
  existing formula works unchanged once a focal length is available — only the
  degenerate branch needs a second path.

### 2.3 [S2] The five-frame cap is a memory workaround, not an optimum

More views is strictly better for specular rejection. Five is set by native
memory: at 12 MP, five `CV_8UC3` sources plus five aligned plus five masks is
roughly 420 MB at peak, mitigated somewhat because `BurstRegistration` releases
each source as it goes. Raising the count needs **tiled processing** — fusing in
horizontal bands, holding only the current band of each aligned frame. That is
the unlock for 8–12 view fusion, and a real project rather than a patch.

### 2.4 [S2] Registration tolerance is scale-blind, and one homography cannot model a print

`BurstRegistration` accepts a frame at `meanResidual <= 3.0` pixels. At 12 MP
that is a 3 px mean error on a ~4000 px image — enough to blur fine grain when
fusion then chooses between frames per pixel. Make it relative to image width
(≈ `0.0005 × width`).

Separately, a **single global homography** cannot model print curl, page bow, or
rolling-shutter skew during a handheld sweep, and prints are rarely flat. A
per-tile refinement (ECC or sparse flow residual on a grid) after the global
homography would cut residuals — and is what makes the higher frame counts of
2.3 actually pay off.

### 2.5 [S2] Exposure normalization is applied in gamma space

```kotlin
gain = referenceExposure / exposure       // exposure-time × ISO ratio
image.convertTo(it, CvType.CV_8UC3, gain) // applied to 8-bit sRGB
```

Radiance ratios are linear; the frames are gamma-encoded. A 2× radiance step is
roughly a 1.23× step in 8-bit sRGB, so frames with genuinely different exposure
end up tone-mismatched and fusion's luma comparison mis-ranks them.

In the default sweep AE is locked, so `gain ≈ 1` and the error is small. On the
`QUALITY_YUV` comparator path on devices without manual sensor control, where AE
can drift, it is not. Correct form: linearize → scale → re-encode.

### 2.6 [S2] Glare and saturation thresholds are absolute, not adaptive

`ConservativeFusion` uses fixed constants — `luma >= 250` is "saturated",
`OUTLIER_SPREAD = 24` and `OUTLIER_MEDIAN_LIFT = 10` define a moving highlight.
These do not scale with the print's own tone distribution. A dark print with a
moderate sheen (specular luma ~180) never trips the outlier test; a high-key
print tips the failure map red across legitimate highlights. Deriving the
thresholds from per-tile statistics (e.g. a multiple of the local inter-sample
MAD) would make behaviour consistent across print types.

### 2.7 [S2] Exported images carry no EXIF, no ICC profile, no orientation

`Imgcodecs.imwrite` writes a bare JPEG, so the file inserted into the photo
library has no `DateTimeOriginal` (the system gallery sorts it by insert time,
landing it in the wrong place in the timeline), no `Make`/`Model`/`Software`, no
orientation tag, no `XResolution`/`YResolution` even after the user has recorded
a confirmed print size and the app knows the exact PPI, and no ICC profile.

The framework `android.media.ExifInterface` can edit a JPEG in place from API
24. Small change, disproportionate payoff.

### 2.8 [S3] JPEG chroma subsampling

`IMWRITE_JPEG_QUALITY, 96` with OpenCV's default 4:2:0. For an archival master,
4:4:4 (`IMWRITE_JPEG_SAMPLING_FACTOR`) at q=95 costs a few percent in size and
keeps the chroma detail a photo print scan actually contains.

### 2.9 [S3] The 16-bit TIFF costs time and storage for no information

`app/README.md` is admirably honest that an 8-bit YUV source in a 16-bit
container is not a 16-bit capture. Given that, writing it by default costs a
full-image `convertTo` plus a 40–70 MB encode on every scan. Make it opt-in in
Settings, off by default.

### 2.10 [S3] No final tone or sharpness rendering

No unsharp mask, no local contrast, no black/white point placement. Demosaic and
rectification resampling both soften, which is why comparable scanners apply a
mild capture-sharpening pass. `INTER_LANCZOS4` in `rectify` would be slightly
sharper than `INTER_CUBIC` for a near-1:1 warp. A gentle *optional* "Auto
enhance" is what the `ENHANCE` section is for.

### 2.11 [S3] No lens shading or vignetting correction

`STATISTICS_LENS_SHADING_MAP_MODE_ON` is requested and the map is in the
recorded metadata, but nothing consumes it. Prints fill the frame, so a 10–15%
corner falloff shows directly as darkened edges.

---

## 3. Bugs

### 3.1 [S1] The user never sees what they are about to save

`ReviewActivity` shows `working_image_path` — the **fused, un-rectified** image
— in the corner editor. That is right for corner editing, but it is the *only*
image the review screen ever displays. The rectified output, the entire
deliverable, is never shown. The user taps **SAVE TO PHOTOS** having never seen
the photo.

For a scanner this is the biggest remaining UX hole. A result view, with a
corners-edit mode entered deliberately, is the conventional and correct shape.
It also unblocks [8.1](#81-the-glare-reveal).

### 3.2 [S1] Nothing is ever deleted, and a failed scan is unreachable

Two halves of the same gap.

`ScanProcessor.markFailed` writes `state = "failed"`, and both
`MainActivity.showLibraryDialog` and `SettingsActivity.exportablePackages`
filter to `review`/`accepted`. So a failed scan never appears in the library,
cannot be retried (`ScanQueue.resumePending` treats `failed` as complete),
cannot be exported for diagnosis — and still holds its full acquisition package
on disk. The only trace is a transient status chip.

And there is no delete anywhere: not in the library dialog, not in review, not
in Settings. With [4.4](#44-s1-roughly-half-a-gigabyte-per-scan-never-reclaimed)
this is the practical ceiling on how long anyone can use the app.

Needs: failed scans listed with **Retry** and **Delete**; delete for any scan;
and a retention option.

### 3.3 [S2] `ReviewActivity.onCreate` has no error handling at all

```kotlin
manifestFile = File(requireNotNull(intent.getStringExtra(EXTRA_MANIFEST)))
val bitmap = requireNotNull(BitmapFactory.decodeFile(working.absolutePath, options))
val quadRecord = manifest.getJSONObject("selected_quad")
```

A missing working image, a truncated manifest, a decode failure on a low-memory
device, or a manifest without `selected_quad` all crash the activity outright.
The app writes 25 MB PNGs to external storage and can be killed mid-write, so
this is reachable. It needs a failure state — "this scan could not be opened —
reprocess / delete".

### 3.4 [S3] Status chip never clears

`showStatus` sets `visibility = VISIBLE` and only `startScan()` hides it. An
error, or "Scan ready", stays pinned over the viewfinder indefinitely. It should
fade after a few seconds; errors can persist.

### 3.5 [S3] Developer strings and filesystem paths in the user-facing chip

`Camera2BurstController` emits all of these to `listener.onStatus`, which
`MainActivity` renders verbatim:

- `"Waiting for preview 3A convergence"`
- `"Locking exposure, white balance, and focus"`
- `"Camera rejected preview + YUV/I420 stream combination"`
- `"Sweep started"`, `"Saving 7 sweep frames"`
- `"Capture package: /storage/emulated/0/Android/data/ch.lkmc.kirsch/files/captures/capture-20260725t…"`

The last prints an absolute filesystem path into the UI. All of them are
hardcoded English in Kotlin while everything else lives in `strings.xml`, so the
app is half-localizable. Diagnostics belong in `Log`; the chip should show a
short user-facing string from resources.

### 3.6 [S3] `SweepOverlayView` is invisible to screen readers

The framing hint, the sweep hint, the ring and the percentage are all
`Canvas.drawText`/`drawArc`. TalkBack sees an unlabeled `View`: no
`contentDescription`, no `announceForAccessibility` on progress, no
`AccessibilityNodeInfo`. A blind or low-vision user cannot perform the sweep,
which is the app's entire capture interaction. The status chip is a polite live
region but it is hidden during sweeping.

### 3.7 [S3] `showLibraryDialog` parses every manifest twice on the UI thread

`readText` + `JSONObject` in the filter, then again in the label map, both on
the main thread, and the second pass has no `runCatching` (so a file that
changes between passes crashes). With a few dozen scans on external storage this
is a visible hitch on tapping **Scans**.

### 3.8 [S3] Bitmap and Mat lifecycle leaks

- `CornerEditorView` never recycles its bitmap in `onDetachedFromWindow`; the
  last ~1800 px bitmap (≈13 MB at ARGB_8888) leaks with the view.
- `BurstRegistration.register` throws from
  `require(referenceDescriptors.rows() >= 12)` before releasing
  `referenceKeypoints`, `referenceDescriptors` and the ORB instance. Native, so
  no `OutOfMemoryError` — just retained until finalization.
- `orb.detectAndCompute(gray, Mat(), …)` allocates a throwaway mask `Mat` per
  call, never released.
- `ScanProcessor` releases Mats that `BurstRegistration` already released.
  Harmless (`release()` is idempotent) but it signals unclear ownership.

### 3.9 [S3] `ScanQueue` retains dead activities

The listener passed to `enqueue` is captured by the executor task, so a
destroyed `MainActivity` stays alive until processing finishes — tens of seconds
(see [4.1](#41-s1-processing-is-a-2045-second-black-box-with-no-progress)). Each
`onResume` re-registers the current instance via `resumePending`, so instances
can stack. Should be a weak reference or an application-scoped observable.

### 3.10 [S3] `pendingReviewScanId` does not survive process death

A plain field, never written to `onSaveInstanceState`. Process death between
capture and processing loses the automatic review hand-off. `resumePending`
re-processes but does not re-open review. Minor; the `onResume` recovery path
covers the common case.

### 3.11 [S3] `onResume` skips camera start and queue resume when handing off

The early `return` on the pending-review branch means neither
`startCameraIfReady()` nor `ScanQueue.resumePending` runs on that pass. Correct
in effect — `onResume` fires again on return — but it makes the camera lifecycle
depend on an unrelated branch.

### 3.12 [S3] `configureTransform` assumes a 90°/270° sensor

It forces `contentWidth = min(previewW, previewH)`, hard-coding that the buffer
delivered to the `TextureView` is portrait-shaped. True for the overwhelmingly
common `SENSOR_ORIENTATION` of 90 or 270 in a portrait-locked activity; a device
reporting 0 or 180 would show a wrongly stretched preview. Reading
`SENSOR_ORIENTATION` and branching costs two lines.

---

## 4. Performance and stuttering

### 4.1 [S1] Processing is a 20–45 second black box with no progress

Arithmetic estimate for one 5-frame, 12 MP scan on a mid-range phone:

| Stage | Estimate |
|---|---|
| SHA-256 verification of 5 × 18 MB payloads | 2–4 s |
| I420 → BGR decode ×5 | 1–2 s |
| ORB (8000 features) detect+compute on 12 MP ×5 | 5–12 s |
| kNN match + MAGSAC++ ×4 | 1–3 s |
| `warpPerspective` 12 MP ×4, plus mask warps | 1–2 s |
| `ConservativeFusion` — 60 M inner-loop iterations | 2–6 s |
| `fused.png` — full-res PNG encode | 3–6 s |
| rectify + JPEG + 16-bit TIFF + 2 PNG maps | 4–8 s |
| **Total** | **≈ 20–45 s** |

Throughout, the user sees one static chip: *"Photo captured — processing…"*. No
spinner, no stage, no percentage, no cancel. They can start another scan, which
queues behind it on the same single-thread executor and lengthens the wait with
no indication that it did.

Cheapest large win: staged progress (`ScanQueue.Listener` gains
`onScanProgress(stage, fraction)`). Cheapest large *speed* win: 4.2 and 4.3.

### 4.2 [S2] ORB runs at full 12 MP

Feature detection and description at 12 MP is where most registration time goes
and buys very little. A homography estimated on a 1600 px-wide downscale and
scaled up (`H' = S⁻¹ H S`) is essentially as accurate for a global planar
transform and roughly 10–20× cheaper; the warps still run at full resolution.
Standard, safe optimization.

### 4.3 [S2] `working/fused.png` is a full-resolution PNG

Encoding 12 MP of BGR to PNG is seconds of CPU and ~20–30 MB on disk per scan.
The file exists only so manual corner correction can re-rectify from the
unrectified fused image; a q=98 JPEG (~3 MB, <1 s) or lossless WebP serves that.
The master is unaffected. The 16-bit TIFF ([2.9](#29-s3-the-16-bit-tiff-costs-time-and-storage-for-no-information))
is the other half of this cost.

### 4.4 [S1] Roughly half a gigabyte per scan, never reclaimed

| Artifact | Size |
|---|---|
| 22 × I420 at 12 MP (18 MB each) | **≈ 396 MB** |
| `working/fused.png` | ≈ 25 MB |
| `acquisition-master.tif` (16-bit, 3ch) | ≈ 40–70 MB |
| master JPEG + confidence + failure maps | ≈ 6 MB |
| **per scan** | **≈ 470–500 MB** |

Twenty scans is ~10 GB. There is no storage indicator, no retention policy, no
"delete sources after accept", and no delete action anywhere
([3.2](#32-s1-nothing-is-ever-deleted-and-a-failed-scan-is-unreachable)).

Retaining acquisitions is deliberate and right for benchmark sessions. For the
product path it needs, at minimum: a storage figure in Settings, per-scan
delete, and an opt-in "discard acquisition after the scan is accepted" that
keeps the manifest and hashes while dropping the payloads.

### 4.5 [S2] The sweep runs a full-resolution stream at full frame rate

During a sweep the repeating request targets **both** the preview surface and
the 12 MP `ImageReader`. For up to 20 seconds the HAL produces full-resolution
YUV continuously — hundreds of 18 MB frames — of which the policy keeps at most
22. Everything else is analyzed and immediately closed.

Costs: sustained ISP load and thermal throttling (the manifest already records
thermal status, which suggests this was anticipated), ~108 MB of graphics
buffers held, and a capture rate limited by full-resolution readout rather than
by the analysis, so the ring updates at maybe 10–15 Hz instead of 30.

The structurally right design is a small analysis stream (e.g. 640×480 YUV) for
motion measurement, with a full-resolution still requested **only** for frames
the policy keeps. The honest caveat: three concurrent streams (PRIV preview +
YUV analysis + YUV maximum) are not in the guaranteed `StreamConfigurationMap`
combinations for all hardware levels, so this needs a capability check, a
fallback to current behaviour, and device testing. Largest power and thermal win
available; a project, not a patch.

### 4.6 [S2] SHA-256 over every payload, twice

`CapturePackageWriter.fileRecord` hashes each 18 MB payload as it is written;
`CaptureFrameLoader.verifyFile` hashes it again at processing time — ~660 MB of
hashing on write and ~180 MB on read, per scan. The integrity contract is worth
keeping, but the write-side hash can be computed incrementally in the same pass
that writes the bytes (`DigestOutputStream`) instead of re-reading from external
storage.

### 4.7 [S3] `TimestampPairer(maxPendingImages = 4)` against a 6-buffer reader

Four pending images plus in-flight writes leaves almost no slack. When the
reader runs dry the camera pipeline stalls, which shows as a preview freeze
mid-sweep — exactly when the user is being asked to move smoothly. #27 fixed the
correctness half of this; the pressure itself is still there.

### 4.8 [S3] `ConservativeFusion`'s inner loop

60 M iterations of Java array access plus a per-pixel insertion sort. Correct and
allocation-free, but it belongs either in a vectorized OpenCV form or, more
cheaply, row-parallelized: the loop is trivially independent per row, and four
threads on row bands would cut it ~3.5×.

---

## 5. Layout and visual issues

### 5.1 [S2] The UI is `Theme.Material.NoActionBar` with hand-built views

`android.useAndroidX=false`, so there is no Material Components, no
`MaterialButton`, no `ConstraintLayout`. Every screen is `LinearLayout` +
`TextView` + platform `Button` built in Kotlin. Visible consequences:

- **Buttons.** `APPLY CORNERS`, `SAVE TO PHOTOS`, the enhance buttons and
  `PRINT SIZE…` render as stock Material-1 grey raised buttons from ~2014,
  inside an otherwise carefully art-directed dark amber theme.
- **Spinners.** The capture-profile and scale-authority spinners use
  `android.R.layout.simple_spinner_dropdown_item` — black-on-white text dropped
  into a `#0E0D0B` screen.
- **Dialogs.** `AlertDialog.Builder(this)` with the platform theme is a light
  dialog over a dark app. Library, export, capabilities and print-size dialogs
  all flash white.
- **EditText.** The print-size fields set no hint colour (the settings field
  does), so hints can be near-invisible on dark.

None of this strictly needs AndroidX — styled `GradientDrawable` backgrounds and
explicit themed attributes for dialogs and spinners would fix it. Adopting
AndroidX + Material 3 would fix it properly and unlock much else, at the cost of
a large diff.

### 5.2 [S2] The review screen has no navigation and no structure

A single `ScrollView` with a text title. No back arrow, no app bar, no close;
the only way out is the system back gesture. After saving there is no "Done" or
"Scan another" — the user is left on a locked screen.

### 5.3 [S2] The corner editor is small, un-zoomable, and inside a scroll view

`minimumHeight = 280dp`, inline in a `ScrollView`. Precise corner placement on a
~350 dp-tall view of a 4000 px print means each screen pixel is ~10 image
pixels; the magnifier helps but there is no pinch-zoom and no pan. It also
fights the `ScrollView` for vertical drags — `requestDisallowInterceptTouchEvent`
handles it once a handle is grabbed, but a drag starting >48 dp from a handle
scrolls the page instead, which reads as "the editor ignored me". A dedicated
full-screen crop step with pinch-zoom is the right shape, and pairs with
[3.1](#31-s1-the-user-never-sees-what-they-are-about-to-save).

### 5.4 [S3] The framing brackets are decorative

`drawFraming` draws brackets at fixed 12%/18% insets, unrelated to anything
detected. They imply "put the photo inside this box", but the box matches no
print aspect and nothing checks it. Keeping guidance detection-free is
deliberate (`PLAN.md` §7) and should stay — but the fixed box could at least
follow the preview's aspect ratio, and the hint could say what it means.

### 5.5 [S3] The sweep percentage can look stuck

`bestProgress = max(bestProgress, min(coverage, keptCount/minFrames))` is
monotonic and honest, but the number can freeze for seconds while the user is
moving, because motion in an already-satisfied direction adds nothing. The four
ring segments convey this; the big central number is what the eye goes to.
Either drop the percentage in favour of the ring, or annotate it.

### 5.6 [S3] The library dialog shows raw capture IDs

*"capture-20260725t143052117z-a1b2c3d4 · accepted"*. No thumbnails, no
human-readable date, no grid. A photo app's library should be a grid of
thumbnails.

### 5.7 [S3] No landscape, no tablet, no large-screen consideration

`MainActivity` and `ReviewActivity` are `screenOrientation="portrait"`;
`SettingsActivity` is not, so it is the only rotatable screen. Portrait-locked
capture is defensible for this interaction; a portrait-locked *review* screen
for a landscape print is not — the print is shown at half the available width
for no reason.

### 5.8 [S3] Hardcoded colours

Colours are `0xFFF3EDE2.toInt()` literals scattered through five Kotlin files.
`colors.xml` contains exactly one entry (the launcher background). Changing the
accent means editing six files. No `values-night` and no other locales.

---

## 6. Missing features

Ordered by how often a user would want them.

### 6.1 [S1] Share

No share action anywhere. A scanner whose output cannot be sent to anyone is
doing half the job. Needs a `FileProvider` (no network permission required) and
an `ACTION_SEND` chooser. Conspicuous next to the carefully built MediaStore
export.

### 6.2 [S1] Delete and retry

See [3.2](#32-s1-nothing-is-ever-deleted-and-a-failed-scan-is-unreachable).

### 6.3 [S2] PDF export and multi-page

For "document scanner" framing this is table stakes: several scans → one PDF.
`android.graphics.pdf.PdfDocument` is in the framework; no dependency needed.

### 6.4 [S2] Rotate

If `PrintGeometry.order()` picks the wrong starting corner, or the print was
placed sideways, the output is rotated 90° and the user cannot fix it. A
four-way rotate in review is a ten-line feature and a constant real-world need.

### 6.5 [S2] Batch / continuous scanning

Every scan forces a round trip through review. A shoebox of 200 photos means 200
review screens. A "keep scanning" mode that queues scans and reviews them
afterwards is the difference between a demo and a tool. See
[8.5](#85-shoebox-mode).

### 6.6 [S2] Tap-to-focus and exposure compensation

Standard camera affordances, entirely absent; the user cannot tell the camera
where the print is. Note this is *not* the patent-sensitive guidance `PLAN.md`
§7 warns about — it is user-initiated AF metering, not glare-driven or
corner-targeted capture guidance.

### 6.7 [S2] First-run coaching for the sweep

The sweep is an unusual interaction: the user taps a shutter and, instead of
getting a photo, is told to "move the phone in slow circles". Without a one-time
animated explanation of *why* (four viewpoints let the app see past reflections)
and *how far*, the likely first-run behaviour is a small wiggle, a 20-second
timeout, an `endedEarly` warning nobody sees, and a disappointing result.

### 6.8 [S2] Auto-crop confidence feedback

`ScanProcessor` writes all detected quads into `processing-report.json` and the
review screen uses only the first. The user cannot pick a different detected
region and gets no signal about whether detection succeeded or fell back to
`fullFrame`. "We couldn't find the print edges — drag the corners" is
information the app has and does not surface.

### 6.9 [S3] Torch and a low-light warning

No torch control and no "too dark" indication. The sweep's sharpness gate will
quietly reject frames in dim light and the sweep will time out, unexplained.

### 6.10 [S3] Level / tilt indicator

Shooting square-on materially improves geometry and — per
[2.2](#22-s2-single-axis-tilt-is-the-common-pose-and-still-gets-a-wrong-aspect)
— reduces the residual aspect error. The accelerometer is free. Device-attitude
driven, not image-driven, so it stays clear of the guidance constraints.

### 6.11 [S3] Print-size presets

The archival dialog asks for millimetres as free text. 10×15, 9×13, 13×18,
20×25, square Polaroid and "custom" cover ~95% of consumer prints and turn a
typing task into a tap. Could also feed an aspect snap.

### 6.12 [S3] Scan naming and search

Scans are identified by `capture-<timestamp>-<uuid8>` forever. No title, tags,
album, or notes.

### 6.13 [S3] Storage insight

Settings shows nothing about the ~500 MB per scan
([4.4](#44-s1-roughly-half-a-gigabyte-per-scan-never-reclaimed)).

---

## 7. Interface assessment

**What is good, and should be preserved.** The capture screen is clean and
confident — full-bleed preview, one obvious shutter, two pill buttons, a good
dark amber palette. The four-segment ring is a well-chosen metaphor: everyone
has enrolled a fingerprint. The corner editor's magnifier and 48 dp grab radius
are thoughtful. Accessibility live regions on status text show someone was
paying attention. And the honesty discipline — `endedEarly`, `used_fusion`,
`scan_locked` vs `scan_accepted`, "no delivered-resolution claim" — is unusual
and worth keeping exactly as it is.

**What still breaks the experience**, after PRs #22–#27:

1. **The review screen is a form, not a photo.** It shows an un-rectified
   working image, a wall of `ALL-CAPS` headers, several grey buttons and five
   caption paragraphs. The user came to see their photo. (3.1, 5.2)
2. **Nothing indicates progress** for 20–45 seconds of processing. (4.1)
3. **The copy is engineering prose.** *"Applies the corner correction and
   updates the scan"*, *"Recorded in capture packages so benchmark scans can be
   traced to a physical print"*, *"Sampling frequency recorded: 302.3 × 301.8
   PPI. No delivered-resolution claim was made."* The rigour is right and
   belongs in the manifests and reports; the *chrome* should speak to a person
   holding a shoebox of photos.
4. **No haptics, no sound, no motion.** The ring fills, the sweep completes, the
   scan saves — all in silence with no animation. The fingerprint metaphor the
   ring borrows is 90% haptic. (8.3)

**Speed.** Shutter to usable photo in the library is roughly 20 s of sweep +
20–45 s of processing + review interaction ≈ **a minute per photo**, against ~3
seconds for a competing single-shot app. Glare removal justifies some of that;
4.2 and 4.3 alone could roughly halve the processing time, and 4.5 would make
the sweep itself lighter.

---

## 8. Ideas

### 8.1 The glare reveal

The app's whole reason to exist is invisible. After processing, show a
**before/after slider**: the best single frame on one side, the fused result on
the other, with the reflection sliding away under the user's thumb. It uses data
already on disk (`working/fused.png` plus a source frame), it is the most
persuasive thing this app could show, and it turns an abstract 20-second sweep
into a visible payoff. If fusion did not help, the slider shows that honestly
too — which fits this project's temperament exactly. Depends on 3.1.

### 8.2 A motion trail instead of a percentage

`SweepPolicy` already tracks `(positionX, positionY)` — the camera's path
through displacement space. Draw it: a fading comet trail of where the phone has
been, with the four direction targets as faint arcs it needs to reach. Strictly
more informative than "43%", beautiful, and *purely motion-derived*, so it stays
inside the constraint that guidance never reacts to image content or print
position. Pairs with recording those positions for 2.1.

### 8.3 Haptics, borrowed properly — blocked pending claim charts

One crisp `HapticFeedbackConstants.CONFIRM` tick as each ring segment completes,
and a double pulse on completion. That is the entire reason fingerprint
enrollment feels good, and it is roughly six lines of code.

**It is not six lines of decision.** `PLAN.md` §7.2 names *haptic guidance*
alongside a live glare map, quad overlay, spoken instruction, progress metric
and learned fusion as things that "must all be included in the final claim
charts". Adding haptics to sweep guidance is exactly that item. PR #21 reached
the same conclusion independently and parked it. It stays parked until the
counsel review `PLAN.md` §7.1 calls for, and it should not be picked up as an
easy win — this entry originally listed it as one, which was wrong.

### 8.4 Cherry

The app is named after cherry brandy and the icon is a cherry on a scanner, and
then nothing in the app mentions it again. The empty library says *"No scans yet
— scan your first photo"*. It could say something with a cherry in it. The
distillery metaphor is right there: captures are the *mash*, the fused master is
the *distillate*, derivatives are *casks*. This is a personal project — it is
allowed to have a personality.

### 8.5 Shoebox mode

Point, sweep, and instead of going to review, snap back to the viewfinder with a
thumbnail sliding into a stack in the corner. Scan thirty photos in ten minutes;
review the stack afterwards over coffee. Pair with a counter: *"23 scanned · 2
need a look"*.

### 8.6 The back of the photo

Handwriting *recognition* is correctly gated on a missing model. But a photo of
the back, stored as a sibling derivative and shown behind a flip animation in
review, needs no model at all — and the writing on the back is very often the
most valuable thing about an old print. A literal card-flip gesture on the
result view would be delightful and genuinely useful.

### 8.7 Print-size aspect snapping

When the recovered ratio (now available from #23) lands within ~1.5% of a
standard print size, snap to it and say so quietly: *"Looks like a 10 × 15 —
snapped."* Users like a machine that recognises a physical object correctly, and
it makes the geometry exactly right rather than approximately right. Deliberately
left out of #23 as a product decision rather than a geometry fix.

### 8.8 A scan's own provenance page

The manifest already holds a full derivative graph with hashes, parents,
recipes, timestamps, thermal state and processing latency. Nobody ever sees it.
A tasteful "how this scan was made" sheet — 7 frames kept from a 14-second
sweep, 5 registered, fusion accepted, 302 PPI from confirmed dimensions — is
both a debugging tool and, for an archival tool, quietly reassuring.

### 8.9 Live level with a satisfying snap

A faint horizon line that turns amber and ticks once when the phone is within 2°
of parallel to the print, from the accelerometer rather than the image. Improves
geometry (see 2.2), costs nothing, feels like a real instrument.

### 8.10 Time-machine sort

Scans carry the date they were scanned, not the date of the photo. Letting the
user tag an approximate year and seeing the library as a timeline would turn a
pile of files into a family archive.

---

## 9. Documentation drift

- `PHASES1-3.md` says the sweep keeps "minimum five frames, maximum **twelve**".
  `CaptureProfile.SWEEP.frameCount` and `SweepPolicy.Settings.maxFrames` are both
  **22**, and `app/README.md` says twenty-two. The phases record is stale.
- `app/README.md` says processing "selects up to five evenly spaced frames from
  the acquisition to bound native memory (sweep keeps are already
  displacement-spaced)". The parenthetical is not true in a useful sense — the
  keeps are spaced from each other in *time*, and the selection ignores
  displacement entirely. See [2.1](#21-s2-only-5-of-up-to-22-swept-frames-are-used-and-they-are-chosen-by-clock).
- `Camera2BurstController.start(mode: CaptureMode)` is dead code — nothing calls
  the `CaptureMode` overload; `MainActivity` always passes a `CaptureProfile`.
- `ScanState.PREVIEW/CONVERGING/CAPTURING/PERSISTING` exist in the state machine
  and its transition table but nothing ever transitions through them —
  `ScanProcessor` starts at `QUEUED`. The capture-side states are aspirational.

---

## 10. Ranked next steps

By user-visible improvement ÷ risk, given #22–#27:

1. **3.1** — show the rectified result in review. The user still cannot see what
   they are saving, and it unblocks 8.1 and 5.3.
2. **3.2 + 4.4** — delete, retry, and a retention policy. The app is unusable
   past ~20 scans on a 128 GB phone.
3. **4.1** — processing progress. Nothing changes but the perception, and the
   perception is currently "it hung".
4. **2.7** — EXIF on the exported JPEG. Small change, every scan affected.
5. **2.1** — select processing frames by displacement, not clock. Requires
   recording sweep positions first; makes the sweep's whole premise pay off.
6. **4.2 + 4.3 + 4.6** — the three contained speed fixes; together they should
   roughly halve processing time.
7. **2.2** — record `SENSOR_INFO_PHYSICAL_SIZE`, thread intrinsics into
   processing, and fix the single-axis aspect case.
8. **6.1, 6.4, 6.6** — share, rotate, tap-to-focus. Small, obviously missing.
9. **6.7** — sweep coaching. The interaction's success rate depends on the user
   understanding it. (Its natural companion, haptics, is blocked on claim
   charts — see [8.3](#83-haptics-borrowed-properly--blocked-pending-claim-charts).)
10. **5.1** — the visual pass, whether by styling in place or by adopting
    AndroidX and Material 3.

Then, if the appetite exists, the two structural projects: the low-resolution
sweep analysis stream ([4.5](#45-s2-the-sweep-runs-a-full-resolution-stream-at-full-frame-rate))
and tiled fusion over more views ([2.3](#23-s2-the-five-frame-cap-is-a-memory-workaround-not-an-optimum)).
