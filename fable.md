# Kirsch — full project review (Fable)

**Reviewed:** 2026-07-16, at commit `f7529e8` (post-PR-#12 main).
**Scope:** every Kotlin source in `app/`, the manifest/resources/build files, and the
project documentation (`PLAN.md`, `PHASE0.md`, `PHASES1-3.md`, `app/README.md`,
benchmark tooling docs). This is a code-and-design review; no device was available, so
runtime claims are reasoned from the code and the Android platform contract, and are
labeled with a confidence level.

Each finding has an ID (`B` bug, `Q` scan quality, `P` performance, `V` visual/layout,
`M` missing feature, `U` UX, `D` delight/idea, `G` general). Findings selected for
immediate implementation are marked **[implementing → PR]**; everything else is
carried into `ANALYSIS.md` as future work.

---

## 0. The reported issue: "talking to Chrome in full-screen split mode gets the incorrect URL and can't save the form"

I searched the entire repository for any Chrome, WebView, URL, HTTP, or form-submission
surface. **There is none.** Kirsch has no network permission, no WebView, no browser
integration, and no URL handling of any kind (`AndroidManifest.xml` declares only
`CAMERA`; the only "form" in the app is the print-size `AlertDialog` in
`ReviewActivity`). The described symptom — a wrong URL and an unsavable browser form —
cannot originate in this codebase. It most likely refers to a different tool in your
workflow (e.g. a browser-side assistant extension reading the active tab while in
split-screen); from this repo I can't fix that.

What Kirsch **does** get wrong in split-screen / multi-window mode is real, though, and
worth fixing on its own merits:

- **B1** — the camera preview transform assumes a portrait, full-screen window, so in
  split-screen (where Android ignores `screenOrientation="portrait"`) the preview
  renders stretched or sideways.
- **B2** — with `targetSdk = 35`, Android 15 forces edge-to-edge rendering and the app
  handles no window insets, so controls can sit under the navigation/status bars —
  most visibly in split-screen and gesture-nav configurations. Buttons under a system
  bar are a plausible real-world path to "can't press save".

Both are fixed in this round (see below). If the report *was* about Kirsch, please
retest in split-screen after these land; if it was about a browser assistant, it's out
of this repo's reach.

---

## 1. Bugs

### B1. Preview transform is wrong in any non-portrait window (split-screen, multi-window, large screens) — **[implementing → PR]**
`MainActivity.configureTransform()` (`MainActivity.kt:239`) corrects only the aspect
ratio and hard-assumes the content is portrait (`contentWidth = min(w,h)`,
`contentHeight = max(w,h)`), never consulting `display.rotation`. The manifest locks
`screenOrientation="portrait"`, but Android **ignores that lock in multi-window /
split-screen mode**, and increasingly on large screens and foldables. In those windows
the TextureView content is stretched and/or rotated 90°. The fix is the standard
Camera2 `TextureView` transform: compare display rotation with the buffer orientation,
rotate/scale accordingly, and center-crop, recomputing on layout and configuration
changes.

### B2. No edge-to-edge/window-inset handling; broken layouts on Android 15 (targetSdk 35) — **[implementing → PR]**
Android 15 enforces edge-to-edge for apps targeting SDK 35: `statusBarColor` /
`navigationBarColor` from `styles.xml` are ignored and content draws behind system
bars. No activity handles `WindowInsets`:
- `MainActivity`: the bottom control row (`buildUi`, 36 dp bottom padding) can sit
  partially under the gesture-nav bar; the title/status chip can collide with the
  status bar/cutout.
- `ReviewActivity` / `SettingsActivity`: the `ScrollView` content starts under the
  status bar; the last button can end under the nav bar.
Fix: apply `setOnApplyWindowInsetsListener` padding for system bars + display cutouts
in all three activities.

### B3. Restored derivatives are invisible and are never what gets saved
`DerivativeStore.createRestoration()` writes `restored-*.jpg` and records it in the
manifest, but does **not** update `preview_path`; `ReviewActivity` never displays
derivatives; and **Save to Photos** exports `preview_path` only
(`ReviewActivity.exportToGallery`, `ReviewActivity.kt:251`). Net effect: a user taps
"Fade correction", sees "Saved a new copy: restored-fade-….jpg", taps SAVE TO PHOTOS —
and the *uncorrected* image lands in their photo library, with the corrected file
stranded in app-private storage, unviewable in-app. Keeping the master pristine is
correct archival policy; silently discarding the user's chosen enhancement is not.
**[implementing → PR]** — at save time, when restored derivatives exist, show a
version chooser (master is the default) so the exported JPEG is an explicit choice.
Full derivative viewing/compare in the review screen remains follow-up work (M2).

### B4. The review screen never shows the final output
The corner editor always renders `working_image_path` (the pre-rectification fused
image). After APPLY CORNERS the new rectified output becomes `preview_path`, but
nothing on screen shows it; the user saves blind. Needs a result preview (small,
tappable, updated after each apply). Deferred: needs modest UI restructuring; see M2.

### B5. Raw filesystem path shown to the user in the status chip — **[implementing → PR, polish]**
`Camera2BurstController.finalizeActiveWriter()` calls
`status("Capture package: ${writer.outputDirectory()}")`, which puts an
`/storage/emulated/0/Android/data/...` path in the UI chip for every capture (it is
usually replaced moments later by "Photo captured — processing…", but flashes on every
scan and is the *only* message in RAW mode until the RAW string arrives). Belongs in
logcat, not the chip.

### B6. Un-applied corner drags and pending review hand-off are lost on activity recreation
- `ReviewActivity` keeps dragged corner positions only in the view; rotation or a
  theme change recreates the activity and silently resets to the manifest quad.
- `MainActivity.pendingReviewScanId` is a plain field; process death or recreation
  while processing drops the automatic review hand-off (the "Scan ready" chip still
  appears). Low severity, worth `onSaveInstanceState` handling later.

### B7. `onSurfaceTextureDestroyed` returns `true` while the camera thread may still hold the surface
`MainActivity` returns `true` (releasing the SurfaceTexture) immediately, while
`controller.stop()` only *posts* teardown to the camera thread. Usually harmless
(HAL logs a warning), but returning `false` and letting the controller release it, or
synchronizing teardown, is the robust pattern. Low priority.

### B8. Settings uses deprecated `startActivityForResult` API
Works fine today (`SettingsActivity.kt:62`); migrate to Activity Result APIs when the
screen is next touched. Cosmetic/tech-debt.

### B9. Decimal input may fight locales
The print-size dialog uses `TYPE_NUMBER_FLAG_DECIMAL` plus `toDoubleOrNull()`. Some
keyboards emit `,` for decimals in European locales, which `toDoubleOrNull` rejects →
"Invalid physical dimensions". Accept both separators when parsing. Small fix, fold
into any future ReviewActivity work.

### B10. Back press during a sweep silently discards the capture
`onPause → controller.stop()` marks the active capture failed with no confirmation and
no user-visible explanation beyond a chip that is immediately covered by the framing
hint. Consider an in-sweep back interception ("Cancel this scan?") or at least a clear
"Scan cancelled" message. UX-level; see U4.

---

## 2. Scan quality

### Q1. Fusion operates on gamma-encoded 8-bit YUV, with exposure normalization applied in that space
`BurstRegistration.normalized()` multiplies 8-bit gamma pixels by an exposure-product
gain (clipping at 255). `PLAN.md` §3.3/§6 calls for linear-domain fusion; the current
YUV path is explicitly the evidenced fallback, but the gamma-domain gain means
highlights compress non-physically and the "darkest sample" selection is biased by
tone-curve shape. Documented limitation to carry until the RAW path is validated.

### Q2. Per-pixel single-frame selection with no seam handling
`ConservativeFusion` picks one source frame per pixel (median or dark-quantile on
luma). With AWB/AE locked this is mostly safe, but where frames disagree (residual
misregistration, moving shadows) hard per-pixel switching can leave visible patch
seams; there is no spatial consistency term or feathering. A cheap improvement within
the conservative mandate: blend the 2–3 mid-rank samples instead of copying exactly
one, or feather selection boundaries. Needs benchmark evidence before changing —
carry as an experiment, not a hotfix.

### Q3. Quad detection has no full-resolution corner refinement
`PrintGeometry.detect()` finds quads on a ≤1600 px Canny image and scales corners back
up — up to ~4–8 px of corner error at 12 MP, visible as sliver background borders or
clipped print edges after rectification. `PLAN.md` §3.4 explicitly plans "classical
full-resolution refinement". A `cornerSubPix`-style local refinement around each
scaled corner is well-bounded follow-up work.

### Q4. Single fixed Canny threshold and a 12 % minimum-area gate
Low-contrast prints on similar-toned backgrounds fail detection and silently fall back
to the full frame (`ScanProcessor.kt:88`); prints photographed small in the frame are
excluded by the area gate. Adaptive thresholds / multi-scale detection are follow-up;
at minimum the review screen should make the "no boundary found — full frame used"
case explicit to the user (today they just see corner handles parked at the frame
edges).

### Q5. Fallback single frame is the middle frame, not the sharpest
When registration fails, `ScanProcessor` exports `frames[size/2]`. The loader already
has every frame decoded; picking the sharpest (Laplacian variance — the same metric
`SweepFrameAnalyzer` already uses) would measurably improve the failure path. Small,
but it changes evidence-gated pipeline behavior → recorded as future work with a
benchmark note rather than hot-patched.

### Q6. Rectified output size comes from max opposing edge lengths
`PrintGeometry.rectify()` sizes output by the longer of each opposing edge pair, which
slightly stretches the shorter side; aspect is not cross-checked against the
homography-implied aspect (`PLAN.md` §3.4 "perspective rectification must preserve inferred
aspect ratio"). Minor geometric fidelity item for the benchmark to quantify.

### Q7. Exported JPEG carries no metadata at all (also a UX issue, see M6)
`exportToGallery` streams bare JPEG bytes: no EXIF `DateTimeOriginal`, no software
tag, no `DATE_TAKEN`/`DESCRIPTION` in the `MediaStore` row, and the display name is
the machine ID (`capture-20260716t101530123z-ab12cd34.jpg`). Gallery apps sort these
scans by insert time only and show cryptic names. **[implementing → PR]** — friendly
display name, `DATE_TAKEN`, and EXIF (creation time + software) on the exported copy.

### Q8. Color pipeline is BT.601 YUV→BGR with an sRGB assumption end to end
OpenCV's `COLOR_YUV2BGR_I420` applies limited-range BT.601; modern HALs may emit
BT.601 full-range or BT.709 YUV. No colorimetric claim is made (consistent with the
plan), but a device-matrix check of actual YUV color space vs. the conversion in use
belongs on the Phase-2 color to-do list.

### Q9. Sweep keeps the *first* frames that extend coverage, not the best ones
`SweepPolicy` keeps a frame if it is spaced and extends directional reach and passes a
relative sharpness gate — but within those constraints it is first-come-first-kept;
a sharper frame arriving 100 ms later at essentially the same displacement is
discarded. A "replace kept frame if materially sharper at same coverage" rule could
raise input quality without changing the motion-only guidance contract. Experiment
for the benchmark.

---

## 3. Performance / stuttering

### P1. Review screen decodes a 12 MP PNG on the main thread — **[implementing → PR]**
`ReviewActivity.loadScan()` (`ReviewActivity.kt:44`) runs `BitmapFactory.decodeFile`
on the fused working PNG in `onCreate`/after every task, on the UI thread. A 12 MP PNG
decode is hundreds of ms to seconds on mid-range hardware → the review screen visibly
freezes on open, and every APPLY/enhance re-freezes it. Move decode to a background
thread with a lightweight loading state.

### P2. Library dialog and queue resume do file I/O + JSON parsing on the main thread — **[implementing → PR]**
`MainActivity.showLibraryDialog()` lists every scan directory and reads/parses every
`scan.json` twice (filter + label) synchronously; `ScanQueue.resumePending()` (called
from `onResume`) walks all capture directories and parses every `capture.json` and
`scan.json` on the main thread. With dozens of scans this is a perceptible stall on
every app resume and library open.

### P3. `CornerEditorView` forces whole-view software rendering — **[implementing → PR]**
`setLayerType(LAYER_TYPE_SOFTWARE, null)` (`CornerEditorView.kt:50`) — present only
because `Paint.setShadowLayer` on shapes needs it — makes every drag frame rasterize
the full bitmap in software on the UI thread, and the `Magnifier` snapshots the same
software surface. Corner dragging stutters exactly where precision matters. Replace
shadows with outline strokes (keep contrast) and drop the software layer.

### P4. `Yuv420Packer.copyPlane` copies ~18 M bytes one `ByteBuffer.get(int)` at a time — **[implementing → PR]**
Per 12 MP frame: ~18 million individually bounds-checked absolute `get()` calls plus a
`require` per byte (`Yuv420Packer.kt:86`). A 22-frame sweep pushes ~400 M calls
through the two-thread IO pool — this is the dominant cost of the "Saving N sweep
frames" tail and burns battery. Bulk-copy whole rows when `pixelStride == 1` (the
common case for Y, and for U/V on many devices) and fall back to a strided in-memory
copy otherwise. Unit tests exist (`Yuv420PackerTest`) to pin semantics.

### P5. `ConservativeFusion` is a single-threaded per-pixel Kotlin loop — **[implementing → PR]**
~12 MP × 5 frames with an insertion sort per pixel on one core ≈ seconds of the
processing latency budget. The row loop is embarrassingly parallel; splitting row
bands across a fixed thread pool is a pure speedup with bitwise-identical output
(add a unit test locking output equality vs. the sequential path).

### P6. ORB + brute-force matching runs at full 12 MP
`BurstRegistration` detects 8 000 ORB features per full-res frame and brute-force
knn-matches them. Standard practice is to register on a ~2 MP downscale and scale the
homography (`H_full = S⁻¹·H·S`); typically ~10× faster with equal or better inlier
geometry. Changes evidence-gated numerics → benchmark first, then implement. Carried
forward.

### P7. Full-resolution PNG write for the working image
`ScanProcessor` persists `working/fused.png` (12 MP PNG ≈ several seconds to encode,
15–25 MB on disk, re-decoded by the review screen). Lossless is required for later
re-rectification, but PNG at default compression is the slowest reasonable choice.
Candidates: fast-compression PNG flag, or lossless WebP (smaller, faster decode).
Low-risk, but measure first; carried forward.

### P8. Double SHA-256 of every asset
Every frame payload is hashed on write and again on read (~100 MB+ hashed per sweep
scan each way). Integrity checking is a stated design value; fine — just noting it is
a measurable part of scan latency on big sweeps (potentially skip re-verify when the
package was written by this same process instance).

### P9. Preview may visibly drop FPS during sweep capture
The sweep repeats a `TEMPLATE_STILL_CAPTURE` request targeting the preview *and* the
full-res YUV reader; on devices with a nonzero YUV stall duration the preview cadence
drops during the sweep. Inherent to keeping full-res frames flowing; worth measuring
per device and possibly dropping preview-FPS expectations gracefully. Note only.

---

## 4. Visual / layout issues

### V1. Edge-to-edge breakage — see B2. **[implementing → PR]**

### V2. Review & Settings screens use default-styled widgets on a custom dark theme
Default `Button` chrome (light-grey Material buttons), default `Spinner`, and default
`EditText` underline colors clash with the otherwise deliberate dark/amber design
language of the scanner screen. The review screen — the emotional payoff of the app —
looks like a debug panel. Needs a small design pass: amber-accent buttons, consistent
section spacing, styled inputs. (Kept out of this round to avoid taste-driven churn;
concrete proposal in ANALYSIS.)

### V3. Sweep ring progress jumps discretely — **[implementing → PR, polish]**
`SweepOverlayView` repaints segment fills at the raw measured values; motion updates
arrive in bursts so the arcs jump. Animating the displayed fill toward the target
value (time-based interpolation in `onDraw` + `postInvalidateOnAnimation`) plus a
brief completion flourish makes the interaction feel dramatically better with zero
change to the underlying motion-only semantics.

### V4. Library is a bare text dialog
Machine IDs (`capture-20260716t101530123z-… · ready to review`) in a plain list, no
thumbnails, no dates, no delete. See M1 — this needs to become a real screen.

### V5. Shutter button has no pressed/ripple feedback
An 80 dp custom-drawable `View` with only the busy alpha change. Add a ripple or
pressed-state scale. Minor; fold into the next scanner-screen pass.

### V6. Adaptive icon lacks a `monochrome` layer
Android 13+ themed icons fall back to a shrunken legacy icon. Small asset task.

---

## 5. Missing features

### M1. No real scan library (view, share, delete, storage)
The single most user-visible gap. Needed: a grid of scans with thumbnails and dates,
open-to-review, share, **delete** (see M3), and a storage-usage readout. Today a user
cannot delete anything, cannot share except via Save-to-Photos, and cannot tell that
sweeps are eating storage.

### M2. No derivative viewing / before-after compare in review
Restored derivatives are files you can never look at (B3/B4). The review screen should
render the current output, list derivatives, and offer a press-and-hold before/after
toggle — the confidence/failure maps already exist on disk and would make a genuinely
distinctive "show me what fusion did" overlay (see D2).

### M3. No storage lifecycle for capture packages
Each sweep retains up to 22 × ~18 MB of I420 frames (~400 MB per scan) plus a ~20 MB
working PNG, forever. `PLAN.md` §4.1 explicitly says the default mode deletes source
frames after successful fusion and explicit acceptance, with archival retention
opt-in. Not implemented — there is no deletion code anywhere in the app. Design the
accept-time cleanup (respecting `source_retained` in the manifest and an archival-mode
setting), plus a "Free up space" control.

### M4. No rotate control in review
Landscape-scanned or upside-down prints stay wrong; PhotoScan auto-rotates. A 90°
rotate button (recorded as a derivative operation, like manual rectification) is
cheap and constantly needed.

### M5. No torch, exposure compensation, or tap-to-focus in capture
Dim living rooms are the core use case for family-photo scanning. A torch toggle is
one Camera2 flag (`FLASH_MODE_TORCH`) — but note `PLAN.md` §7.2 treats flash-assisted
capture as patent-relevant (US7457477B2 family context), so gate it on the legal
column before shipping. Exposure compensation and tap-to-focus are uncontroversial.

### M6. Exported photos carry no metadata — **[implementing → PR]** (see Q7)

### M7. No capture-flow conveniences
Volume-key shutter, "scan another" fast loop after save (auto-return to camera), and a
session counter ("3 scans this session") all reduce friction for the 200-photo
shoebox session that is the product's core story.

### M8. No onboarding for the sweep gesture
First-run users get one line of text. A one-time animated demonstration (a hand moving
a phone in a circle, ring filling) would raise sweep completion rates. Keep guidance
fixed/motion-only per the patent constraints.

### M9. Only English strings
`values/strings.xml` has no translations; family-archive scanning is an international
use case. Structure is already resource-based, so this is straight translation work.

### M10. Accessibility gaps
- Sweep progress is announced only as a fixed hint; TalkBack users get no coverage
  feedback. Announce milestone percentages via the existing live region.
- Corner dragging has no non-drag alternative (e.g. select corner, then D-pad nudge).
- The pill buttons meet contrast but not the 48 dp touch-target guideline vertically.

### M11. Processing runs on a bare executor with no lifecycle protection
If the user backgrounds the app mid-processing, the process can be killed and the scan
restarts on next launch (recovery exists via `resumePending` — good), but a
`WorkManager` job or foreground service with progress notification would make
long batch processing dependable and visible. Design decision (notification
permission, UX) — carried forward.

---

## 6. General / architectural notes

### G1. The evidence-gated culture is excellent — preserve it
The honesty infrastructure (fail-closed feature catalog, provenance manifests, hashes,
no fabricated capabilities, immutable accepted revisions) is the most distinctive
engineering asset in this codebase. Future UI work should surface it (D2) rather than
bury it.

### G2. All UI is hand-built in code
No layout XML, no AppCompat/Material dependency (`android.useAndroidX=false`). This
keeps the APK tiny and is defensible at current scale, but every screen addition costs
more and loses system behaviors (predictive back, dynamic color, split-screen polish).
Decision point when the library screen (M1) gets built: either adopt a minimal
androidx set then, or accept a growing custom-view investment.

### G3. Test coverage is good on policies, absent on pipeline
`SweepPolicy`, `Yuv420Packer`, 3A policy, zipper, geometry all have JVM tests. The
OpenCV-dependent pipeline (`ConservativeFusion`, `BurstRegistration`,
`PrintGeometry.detect`, `ScanProcessor`) has none — understandable (needs native
OpenCV loaded), but `ConservativeFusion` is pure logic over `Mat` rows and could be
tested on a desktop OpenCV build (this round's PR adds a determinism test alongside
the parallelization).

### G4. `ScanManifestStore` uses one global JVM lock for all manifest I/O
Correct for a single process; fine now. If a foreground-service/WorkManager processor
(M11) ever runs in a separate process, this lock silently stops protecting anything.
Note attached to M11.

### G5. Version pinning
`versionCode 1 / versionName 0.1.0`, `compileSdk/targetSdk 35` are current. The Gradle
wrapper pins 8.9; AGP 8.5.2 — both fine for SDK 35, worth revisiting at the next
feature milestone.

---

## 7. Delight / novel ideas (for future rounds)

### D1. Confidence overlay: "see what the sweep fixed"
The pipeline already writes `confidence.png` and `failure.png`. A press-and-hold
before/after (best single frame vs. fused) plus an optional confidence heat overlay
would turn the app's honesty into its signature demo moment. No new imaging work —
the artifacts already exist.

### D2. Sweep completion flourish
On coverage completion: ring snap-fill animation + a subtle full-ring glow before the
"Saving frames" phase. (Implemented in the polish PR as a rendering-only change.)
Haptic ticks per segment would be lovely **but** `PLAN.md` §7.2 lists haptic guidance
among the elements that must go into the patent claim charts — get the legal read
before shipping haptics tied to capture progress.

### D3. Print-size presets in the archival dialog
Chips for 9×13, 10×15, 13×18, 3.5×5″, 4×6″, 5×7″ instead of typing millimetres; keeps
the "user-confirmed dimensions" authority semantics, removes the main friction.

### D4. Quick Settings tile / launcher shortcut "Scan a photo"
The shoebox session starts many times; a QS tile and a static app shortcut jump
straight into capture.

### D5. Session flow: stack of finished scans in-corner
During a batch session, show the last few finished thumbnails stacked in a corner of
the scanner (tap → review). Keeps the user in the capture loop instead of bouncing
through the review screen after every print.

### D6. Back-of-photo pairing (Phase 2 alignment)
A "flip and capture the back" prompt after save, storing the back image alongside the
scan (no HTR claim — just the image), would be a cheap, differentiating archival
feature consistent with the feature catalog's honesty rules.

### D7. Shoebox progress ("42 photos preserved")
A tiny lifetime counter in Settings — cheap, warm, and encourages finishing the box.

---

## 8. Implementation round (this review)

Selected for immediate implementation, one branch + PR each, ordered to minimize
overlap (file → PR mapping):

| # | Branch | Findings | Files touched |
|---|--------|----------|----------------|
| 1 | `claude/fix-preview-transform` | B1 | `MainActivity.kt` (transform only) |
| 2 | `claude/fix-edge-to-edge-insets` | B2/V1 | `MainActivity.kt`, `ReviewActivity.kt`, `SettingsActivity.kt` (buildUi roots), `styles.xml` |
| 3 | `claude/fix-main-thread-io` | P1, P2 | `ReviewActivity.kt` (loadScan), `MainActivity.kt` (library/resume), `ScanQueue.kt` |
| 4 | `claude/fix-corner-editor-hwaccel` | P3 | `CornerEditorView.kt` |
| 5 | `claude/perf-yuv-packer` | P4 | `Yuv420Packer.kt` + test |
| 6 | `claude/perf-parallel-fusion` | P5 | `ConservativeFusion.kt` + new test |
| 7 | `claude/feat-save-chooser-metadata` | B3 (chooser), Q7/M6 | `ReviewActivity.kt` (save path), `strings.xml` |
| 8 | `claude/polish-sweep-ring` | V3, B5, D2 (visual part) | `SweepOverlayView.kt`, `Camera2BurstController.kt` (one status line) |

Deliberately **not** implemented this round (documented above instead): anything that
changes evidence-gated imaging behavior (Q2/Q3/Q5/Q6/Q9, P6), product decisions
needing design or legal input (M1, M3, M5 torch, D2 haptics), and larger UI builds
(M1, M2, V2).
