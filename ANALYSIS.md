# Kirsch — analysis and future work

This is the living backlog for Kirsch, consolidated from the full project review of
2026-07-16 ([`fable.md`](fable.md), which stays frozen as the dated review record).
Items the review round already fixed have been cleared out and are listed only in the
completion table below; everything still open is carried here with enough context to
act on without re-reading the original review. IDs match `fable.md`.

## 1. Completed in the July 2026 review round

Fixed in dedicated PRs (one branch per finding cluster, each `gradle
testDebugUnitTest assembleDebug`-verified):

| Finding | Fix | PR |
|---|---|---|
| B1 — preview stretched/rotated in split-screen & multi-window | Rotation-aware Camera2 TextureView transform | #17 |
| B2/V1 — no window-inset handling; broken layouts on Android 15 (targetSdk 35) | Uniform edge-to-edge opt-in + inset padding in all three activities | #18 |
| B3 (export half) — Save to Photos silently ignored restored derivatives | Save-time version chooser; chosen source recorded as `gallery_source_path` | #20 |
| Q7/M6 — exported JPEG had no metadata, machine-ID filename | Friendly display name, `DATE_TAKEN`, EXIF DateTimeOriginal/Software/ImageDescription | #20 |
| P1/P2 — main-thread PNG decode, library/queue-resume file I/O | Background loading with generation guard; single-parse listing; queue-thread resume | #19 |
| P3 — corner editor forced software rendering | Halo strokes instead of shadow layers; GPU rendering restored | #16 |
| P4 — byte-at-a-time YUV plane copies | Bulk row copies + single up-front bounds check (Long-safe) | #14 |
| P5 — single-threaded fusion loop | Row-band parallelism, byte-identical output, safe teardown on failure | #15 |
| V3 — sweep ring jumped discretely | Frame-time eased ring + completion flourish (rendering only, motion-only semantics kept) | #21 |
| B5 — raw filesystem path shown in the status chip | Moved to logcat; product-level messages already cover every outcome | #21 |

Automated-review rounds on these PRs produced a handful of additional hardening
commits (overflow-safe bounds check, executor termination wait, stale-bitmap recycle,
weak resume-listener). Claims refuted with evidence during those rounds are recorded
in the PR threads (#14 in particular).

## 2. The reported split-screen issue — closed as out-of-repo

The report ("talking to Chrome in full-screen split mode gets the incorrect URL and
can't save the form") could not originate in Kirsch: the app has no Chrome, WebView,
URL, HTTP, or browser-form surface of any kind, and no network permission. It most
likely concerns a browser-side assistant in the reporter's workflow. The two real
Kirsch defects adjacent to the symptom — the split-screen preview transform (B1) and
controls under system bars (B2) — are fixed (#17, #18). If the symptom persists in
Kirsch after those merge, re-open with a screen recording.

## 3. Open bugs and robustness

- **B4 — the review screen never shows the final output.** The corner editor always
  renders the pre-rectification working image; after APPLY CORNERS the new rectified
  output becomes `preview_path` but is never displayed, so users save blind. Needs a
  result preview (small, tappable, refreshed after each apply). Pairs with M2.
- **B6 — state lost on activity recreation.** Un-applied corner drags reset to the
  manifest quad on rotation/theme change; `MainActivity.pendingReviewScanId` is a
  plain field, so process death drops the automatic review hand-off. Add
  `onSaveInstanceState` handling for both.
- **B7 — `onSurfaceTextureDestroyed` returns `true` while the camera thread may still
  hold the surface.** Teardown is posted asynchronously; return `false` and let the
  controller release the texture, or synchronize. Low priority, HAL-warning noise.
- **B8 — deprecated `startActivityForResult`** in `SettingsActivity`; migrate to the
  Activity Result API next time the screen is touched.
- **B9 — decimal input vs. locales.** The print-size dialog rejects `,` decimals that
  some keyboards emit; accept both separators when parsing.
- **B10 — back press during a sweep silently discards the capture.** Intercept back
  during an active sweep ("Cancel this scan?") or show a clear "Scan cancelled"
  message instead of a chip immediately covered by the framing hint.
- **New (from #19 review): review-screen load-failure UX.** A failed manifest/bitmap
  load now shows the error in the status line (previously it crashed), but the screen
  offers no retry and may show stale content behind the error. Decide the pattern:
  retry affordance, `finish()`, or clearing the editor.

## 4. Scan quality (evidence-gated — benchmark before shipping)

Per the project's own rules (`PLAN.md` Gate A discipline), none of these should be
hot-patched; each needs benchmark evidence:

- **Q1 — fusion in gamma-encoded 8-bit space.** Exposure normalization multiplies
  gamma pixels (clipping at 255); the darkest-sample selection is biased by the tone
  curve. Carried until the RAW path is validated.
- **Q2 — hard per-pixel frame selection.** No seam handling where frames disagree;
  candidate conservative improvement: blend 2–3 mid-rank samples or feather selection
  boundaries.
- **Q3 — no full-resolution corner refinement.** Quads come from a ≤1600 px Canny
  image; up to ~4–8 px corner error at 12 MP. `cornerSubPix`-style local refinement
  is planned in `PLAN.md` §3.4 and is well-bounded work.
- **Q4 — fixed Canny threshold + 12 % minimum-area gate.** Low-contrast or small
  prints silently fall back to a full-frame quad; at minimum, surface "no boundary
  found" explicitly in review (ties into B4/M2).
- **Q5 — registration-failure fallback uses the middle frame, not the sharpest.**
  The Laplacian-variance metric already exists in `SweepFrameAnalyzer`; selection
  change needs a benchmark note.
- **Q6 — rectified size from max opposing edge lengths** slightly stretches the short
  side; cross-check against homography-implied aspect (`PLAN.md` §3.4).
- **Q8 — BT.601 limited-range YUV→BGR assumed everywhere.** Check actual HAL YUV
  color space per device on the matrix; Phase-2 color work.
- **Q9 — sweep keeps first-come frames.** Consider "replace kept frame if materially
  sharper at same coverage" — stays motion-only, may raise input quality.
- **P6 — ORB + brute-force matching at full 12 MP.** Register on a ~2 MP downscale
  and scale the homography (`H_full = S⁻¹·H·S`); ~10× faster, but changes
  evidence-gated numerics → benchmark first.

## 5. Performance (remaining)

- **P7 — full-res PNG working image** (~seconds to encode, 15–25 MB, re-decoded by
  review). Lossless is required; evaluate fast-compression PNG or lossless WebP.
- **P8 — double SHA-256 of every asset** (~100 MB+ hashed each way per sweep scan).
  Integrity is a design value; consider skipping re-verify for same-process reads.
- **P9 — preview FPS may drop during sweep** (full-res YUV target on a repeating
  still-capture request). Measure per device; degrade gracefully.

## 6. Missing features and product work

- **M1 — a real scan library** (top user-visible gap): thumbnail grid, dates,
  open-to-review, share, delete, storage usage. The current library is a text dialog
  of machine IDs (V4).
- **M2 — derivative viewing / before-after compare in review.** Restored derivatives
  are currently viewable only via SAF export; add rendering of the current output,
  a derivative list, and press-and-hold before/after. The confidence/failure maps
  already on disk enable a distinctive "show what fusion did" overlay (D1).
- **M3 — storage lifecycle.** Sweeps retain up to ~400 MB of I420 sources per scan
  forever; `PLAN.md` §4.1 says the default mode deletes source frames after
  successful fusion and explicit acceptance (archival retention opt-in). No deletion
  code exists anywhere. Design accept-time cleanup honoring `source_retained` and an
  archival-mode setting, plus a "Free up space" control.
- **M4 — rotate 90° in review**, recorded as a derivative operation like manual
  rectification. Constantly needed; PhotoScan auto-rotates.
- **M5 — capture aids:** torch toggle (**gate on counsel review** — `PLAN.md` §7.2
  puts flash-assisted capture in the claim-chart set), exposure compensation,
  tap-to-focus. The latter two are uncontroversial.
- **M7 — capture-flow conveniences:** volume-key shutter, auto-return to camera
  after save ("scan another" loop), session counter.
- **M8 — first-run sweep onboarding:** one-time animated demonstration; keep
  guidance fixed/motion-only per the patent constraints.
- **M9 — translations.** English-only strings today; structure is already
  resource-based.
- **M10 — accessibility:** announce sweep milestone percentages via the existing
  live region; non-drag corner adjustment (select + D-pad nudge); 48 dp touch
  targets for the pill buttons.
- **M11 — processing lifecycle protection.** Queue recovery exists, but a
  WorkManager job / foreground service with progress notification would make long
  batch processing dependable and visible. Note: `ScanManifestStore`'s global JVM
  lock protects nothing across processes (G4) — keep single-process or move the
  lock story along with this work.

## 7. Visual and UX polish

- **V2 — review/settings screens use default widget chrome** on the custom dark
  theme; the payoff screen looks like a debug panel. Small design pass: amber-accent
  buttons, styled spinner/inputs, consistent spacing.
- **V5 — shutter button has no pressed/ripple feedback.**
- **V6 — adaptive icon lacks a `monochrome` layer** for Android 13+ themed icons.
- **U (from B10) — in-sweep cancellation UX** (see B10 above).

## 8. Delight ideas (future rounds)

- **D1 — "see what the sweep fixed":** press-and-hold before/after (best single
  frame vs. fused) + optional confidence heat overlay. The artifacts already exist;
  this turns the app's honesty infrastructure into its signature demo moment.
- **D2 — haptic ticks on segment fill/completion.** Lovely, but `PLAN.md` §7.2 lists
  haptic guidance among claim-chart items — needs the legal read first. (The visual
  completion flourish shipped in #21; it introduces no new guidance modality.)
- **D3 — print-size preset chips** (9×13, 10×15, 13×18, 3.5×5″, 4×6″, 5×7″) in the
  archival dialog; keeps the confirmed-dimensions authority semantics.
- **D4 — Quick Settings tile / launcher shortcut** straight into capture.
- **D5 — session stack:** last few finished thumbnails stacked in a corner of the
  scanner during a batch session (tap → review) to keep users in the capture loop.
- **D6 — back-of-photo pairing:** "flip and capture the back" after save, storing
  the back image alongside the scan — no HTR claim, consistent with the feature
  catalog's honesty rules.
- **D7 — lifetime counter** ("42 photos preserved") in Settings.

## 9. Architecture notes

- **G1 — preserve the evidence-gated culture.** The fail-closed feature catalog,
  provenance manifests, and immutable accepted revisions are the codebase's most
  distinctive asset; surface them in UI (D1) rather than bury them.
- **G2 — all-code UI, no androidx.** Cheap and tiny today; the library screen (M1)
  is the natural decision point for adopting a minimal androidx set vs. continuing
  custom views.
- **G3 — pipeline test gap.** Policies are well-tested; the OpenCV-dependent
  pipeline has no tests because plain JVM unit tests can't load the Android OpenCV
  native library. A desktop-OpenCV test harness (or instrumentation tests) would
  let fusion/geometry changes be pinned — including the parallel-fusion
  byte-identity property from #15.
- **G4 — `ScanManifestStore`'s single JVM lock** stops protecting anything if
  processing ever moves out-of-process (see M11).
- **G5 — version pins** (AGP 8.5.2 / Gradle 8.9 / SDK 35) are current; revisit at
  the next feature milestone. Note: the Gradle wrapper download URL redirects to a
  host some proxies reject; a system Gradle ≥ 8.9 builds the project fine.
