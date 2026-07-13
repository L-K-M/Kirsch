# PLAN.md — A 2026 successor to Google PhotoScan

**Project codename: Kirsch** *(after the Kirsch edge-detection operator — fitting for an app whose first job is finding the edges of a photograph)*

This document synthesizes a research pass (July 2026) over the imaging-science literature, platform capabilities, patent landscape, and competitive landscape relevant to building a modern version of Google PhotoScan: a phone app that digitizes printed photos by capturing multiple views, fusing them to remove glare, then cropping, rectifying, and enhancing the result.

---

## Table of contents

1. [Why now — the opportunity](#1-why-now--the-opportunity)
2. [How the original PhotoScan works, and where it falls short](#2-how-the-original-photoscan-works-and-where-it-falls-short)
3. [What has changed since 2016 — research survey](#3-what-has-changed-since-2016--research-survey)
4. [Product vision and differentiators](#4-product-vision-and-differentiators)
5. [Proposed system architecture](#5-proposed-system-architecture)
6. [Capture UX design (patent-constrained)](#6-capture-ux-design-patent-constrained)
7. [Technology and platform choices](#7-technology-and-platform-choices)
8. [Data strategy](#8-data-strategy)
9. [Evaluation strategy](#9-evaluation-strategy)
10. [Licensing audit](#10-licensing-audit)
11. [Risks and open questions](#11-risks-and-open-questions)
12. [Phased roadmap](#12-phased-roadmap)
13. [Key references](#13-key-references)

---

## 1. Why now — the opportunity

**The incumbent is abandonware.** Google PhotoScan launched November 15, 2016. Its last feature update (v1.7, a Material You reskin with zero imaging changes) shipped November 2022; the final bug-fix build (v1.7.1) is from July 2023. By 2025–2026, user threads report the app **won't even install** on new devices (Galaxy S25+, Pixel 7a, Pixel Tablet) — consistent with a stale `targetSdk` causing the Play Store to hide it. Google shipped no successor and never integrated scanning into Google Photos. Yet its 4.8-star iOS rating (~89k ratings) shows demand persists, and 2026 "best photo scanner app" roundups *still* rank this frozen app as the glare-removal leader. A decade-old technique remains state of the practice.

**The rest of the field retreated or stagnated:**

| Player | Status (mid-2026) |
|---|---|
| Google PhotoScan | Frozen since 2022/2023; failing to install on new hardware |
| Microsoft Lens | Retired — delisted Nov 2025, scanning disabled Dec 2025 (~322k downloads/month at death) |
| Photomyne | Active; $59.99/yr subscription; strong album-page batch scanning but low effective resolution, no multi-frame glare removal, heavy upsell complaints |
| MyHeritage Reimagine | Active (2023); capture + AI restoration, but cloud-only, buggy, over-processed, subscription-only ($49.99/yr) |
| Epson FastFoto FF-680W | Hardware alternative; 2018 design, $529, no successor in ~7 years; can't scan photos glued in albums or framed behind glass |
| Mail-in services (ScanCafe etc.) | $0.34–0.48/photo, 6–8 week turnaround, requires shipping irreplaceable originals |

**The market is real and growing.** Photo digitization services: ~$2.6B (2024) → projected $5.3B by 2032 (9.3% CAGR). Photomyne's a16z Top-50 consumer GenAI listing (2024) confirms nostalgia/restoration is a genuine consumer AI category. Aging photo collections plus the genealogy boom make demand durable.

**And the science has moved.** Every stage of PhotoScan's pipeline — alignment, fusion, glare removal, boundary detection, rectification, enhancement — now has a dramatically better replacement, and 2026 phone NPUs can run them on-device. Section 3 details this.

*(Note: PhotoScan launched in 2016, not 2003 — but the sentiment is right: its imaging pipeline hasn't meaningfully changed since ~2017.)*

---

## 2. How the original PhotoScan works, and where it falls short

### 2.1 The pipeline (per Google Research's April 2017 blog post)

1. **Capture:** one reference shot (defines the output viewpoint), then the user moves an on-screen circle over **4 dots near the corners**, auto-capturing 4 more frames. The camera motion is the point: specular glare *moves* between viewpoints, so every pixel is glare-free in at least one of the 5 frames.
2. **Alignment:** ORB descriptors on Harris corners → per-frame **homography** to the reference; refined with **grid-based optical flow** (flow at cell corners, bilinear interpolation inside — ~40× cheaper than dense flow on a 2016 Pixel) to handle non-planar (curled) prints.
3. **Glare removal:** a **soft-minimum composite** — take (approximately) the darkest observed value per pixel across aligned frames, down-weighting pixels near warped frame boundaries to avoid seams. Glare is treated as a bright outlier; there is no explicit reflection-layer model. This is an acknowledged simplification of the authors' own SIGGRAPH 2015 "Obstruction-Free Photography" layer decomposition, which was too slow for 2016 phones.
4. **Crop/rectify/rotate:** heuristic color/edge analysis finds the photo's quad boundary; a geometric transform rectifies it; smart rotation orients it. Users can manually adjust corners.

### 2.2 The documented defect list (what a successor must beat)

- **Artificial ~3MP resolution cap** (2000px long side at launch, 3000px on "high-end" devices from v1.1) plus heavy JPEG compression. A modded APK unlocked 12MP output, proving the cap was software, not hardware. Reviewers measured ~2.6MP effective output vs ~34.5MP from a flatbed for the same 4×6 print. **This was the #1 complaint for a decade.**
- **Unreliable corner/edge detection** — fails on complex backgrounds, decorative borders, dark corners; the most common complaint after resolution.
- **No handling of curl, white borders, matte texture, or halftone prints**; glossy prints under room light still defeat it; photos behind glass or in album sleeves are officially "remove it first."
- **One photo at a time, ~30–60s each.** A typical family archive (3–4,000 prints) takes 8+ hours. No album-page batch mode. Throughput, not just quality, is why people abandon digitization mid-archive.
- **No restoration.** No denoise, descreen, scratch removal, fade correction, colorization, or upscaling. (Google built Photo Unblur for Pixel in 2022 and never wired it in.)
- **No metadata workflow.** No back-of-photo capture, no date/caption OCR, JPEG-only output, no color management, no DPI tagging (PhotoScan writes no physical-size metadata at all), tight coupling to Google Photos backup.

### 2.3 What to keep — and one hard constraint

The **physics is still right in 2026**: moving the camera displaces specular highlights, and a robust multi-frame composite recovers *true* content rather than hallucinating it. Multi-view fusion remains the most artifact-free, trustworthy glare-removal signal for glossy prints. We keep the principle.

But the *mechanism as productized* is **not in the public domain**: Google holds two active patent families covering (a) the corner-dot guided-capture composite flow nearly verbatim and (b) detect-glare→instruct-movement→min/median-composite, both in force to ~2036–2037. This constrains the capture UX, not the fusion math — see §6 and §11.

---

## 3. What has changed since 2016 — research survey

### 3.1 Reflection & glare removal

Three generations of progress:

- **Task-specific CNNs (2019–2023):** ERRNet, IBCLN, location-aware SIRR, DSRNet — released code, now clearly outperformed. Useful only as baselines.
- **Transformer/prior models (2024–2025):** **RDNet** (CVPR 2025) won NTIRE 2025 single-image reflection removal and underpins all three NTIRE 2026 winners. The current best open *deterministic* (non-generative) remover.
- **One-step diffusion (2025–2026):** **Dereflection Any Image** (AAAI 2026) and Huawei's **WindowSeat** (Apache-2.0 weights, CVPR 2026 NTIRE) define the quality ceiling; both were used as *distillation teachers* by NTIRE 2026 winners. Multi-GB, server-class — but distillable.
- **The mobile existence proof:** Adobe's *Removing Reflections from RAW Photos* (arXiv 2404.14414) — a **2.5M-parameter** model trained on physically-synthesized linear-RAW mixtures, running in **~1 second on an iPhone 14 Pro**, shipped in Camera Raw/Lightroom (Dec 2024). Near-SOTA quality fits in a few MB if you (a) work in RAW/linear space, (b) invest in physically accurate synthetic training data. Notably, no Adobe patent application on this method has published yet (watch item — 18-month publication lag).
- **Print-specific literature now exists:** glossy-print glare is closer to *document specular-highlight removal* (broad sheen on paper) than to window reflections. Datasets: **DocHR14K** (14,902 real pairs, 2025), **SHDocs** (NeurIPS 2024), SHIQ (2021).
- **Multi-frame methods matured:** learned obstruction removal (CVPR 2020), Samsung's burst Reflection-Motion-Aggregation cues from handshake alone (WACV 2023), **Neural Spline Fields** (CVPR 2024) — test-time optimization over an unstabilized RAW burst that jointly super-resolves and separates layers (a "max quality" cloud-tier candidate).
- **Flash/no-flash cues:** flash-RAW-minus-ambient-RAW gives a physically reflection-free guide image (CVPR 2021); Flash-Split (CVPR 2025) makes it robust to handheld misalignment. Phones have flashes; a scanning app controls capture timing. The foundational Microsoft flash/no-flash patent (US7457477B2) **expired July 2024** — this technique is public domain. Caveat: flash speculars on high-gloss paper saturate; and flash frames are per-pixel *mixtures* of flash and room light, so they must not be naively min-composited with ambient frames (see §3.7).
- **Polarization:** works in the lab (PolarFree, CVPR 2025) but **no consumer phone has a polarized RGB sensor** — do not build the product around it; optionally support clip-on CPL as a pro accessory.
- **Industry signal:** OPPO ships on-device AI reflection removal (<4s); mid-range NPUs run eraser-grade models locally. Nobody combines this with a multi-shot print-scanning pipeline. That's our lane.

### 3.2 Burst fusion, alignment, and super-resolution

- **Google's own lineage is now open:** HDR+ (SIGGRAPH Asia 2016) and **Handheld Multi-Frame Super-Resolution** (SIGGRAPH 2019, the Pixel's Super Res Zoom) both have peer-reviewed open reimplementations (IPOL 2021/2023). Handheld MFSR fuses a RAW burst via anisotropic kernel regression with per-pixel robustness masks — **simultaneously demosaicing, denoising, and super-resolving**. Its robustness-mask machinery is exactly where a glare/outlier term plugs in. This is the gold-standard merge core for a scanner: the multi-frame burst is precisely the input multi-frame SR needs. The resolution cap becomes resolution *upside*.
- **Deep burst SR** (DBSR → BIPNet → Burstormer → 2024-26 Mamba/one-step-diffusion variants): NTIRE 2025's efficiency track proved a **3.3M-param align-then-restore network runs in ~64ms** (0.28 TFLOPs); DRIFT (2026) runs an 11-frame 12MP NAFNet burst pipeline in **3.2s on a Snapdragon 8 Elite NPU**. Plain NAFNet-class CNNs beat exotic architectures for mobile.
- **Alignment is essentially solved for planar targets:** XFeat (CPU real-time, Apache-2.0) or open-retrained SuperPoint + **LightGlue** (Apache-2.0) matches → OpenCV **MAGSAC++** (`USAC_MAGSAC`) homography — near-failure-free, milliseconds, glare blobs simply become outliers. Dense residual flow for page curl: **SEA-RAFT** (BSD-3) / NeuFlow v2 (>20 FPS on Jetson-class hardware) replace the 2016 grid-flow approximation. **RoMa v2** (MIT) serves as an offline teacher/cloud fallback, and its per-pixel certainty doubles as merge weight maps.
- **Platform unblocked:** CameraX 1.5 (Nov 2025) exposes **RAW/DNG capture** (`OUTPUT_FORMAT_RAW_JPEG`); Camera2 interop provides AE/AWB lock and `captureBurst`. Merging in linear RAW — impossible for a third-party app in 2016 — is now the right default on Android. (iOS requires a different capture design; see §3.5.)

### 3.3 Boundary detection, cropping, rectification

- **Industry-proven recipe:** a tiny (<10MB, MobileNet-class) corner-keypoint CNN at ~96–224px for 25–30 FPS live tracking, IMU-fused Kalman smoothing, then a heavier capture-time pass + classical sub-pixel refinement. Genius Scan documented detection accuracy going 51% → 85% almost entirely via *data* (synthetic hard cases + refinement net). Data, not architecture, is the lever.
- **Segment Anything family:** MobileSAM / EdgeSAM / SlimSAM (~5–10M params) make promptable, arbitrary-background segmentation on-device viable; EdgeTAM hits 16 FPS on an iPhone 15 Pro Max. Enables tap-to-fix corrections and **multi-photo album-page splitting** (Photomyne's headline feature, ~92% accuracy — plan a manual-edit fallback). Note: Photomyne holds a patent on NN album-page segmentation (US10452905B2, to 2035), but its independent claim requires WLAN transmission of the cropped files — on-device-only processing is a clean non-infringement position; verify the continuations (§11).
- **Document dewarping matured:** DocUNet (2018) → DewarpNet (2019) → DocTr++ (handles partially-visible documents) → **UVDoc** (2023, small grid-based net, most on-device-friendly) → **DocRes** (CVPR 2024, **MIT license**, unifies dewarp/deshadow/appearance/deblur) → diffusion dewarpers (2025). Curled and dog-eared prints — a documented PhotoScan failure — are directly addressed; run once per capture on the NPU, not per frame.
- **Rectification correctness:** 4-point homography plus **Zhang–He closed-form focal-length/aspect-ratio recovery**, then snap to standard print ratios (3.5×5, 4×6, 5×7, 8×10, square). Naive "stretch quad to rectangle" subtly distorts aspect ratio — a visible quality bug in the 2016 generation.
- **Sub-pixel corners matter at modern resolution:** the NN detects corners at ~224px but the crop applies to a 12–48MP frame; a 1px error at inference scale is 25–100px at full res. Full-res patch refinement (classical `cornerSubPix` or the ECCV 2024 learned sub-pixel module) is mandatory for archival crops.
- **Platform floor:** ML Kit Document Scanner (2024) and Apple's `VNDetectDocumentSegmentationRequest` give free on-device detection — good for A/B baselining, but single-document, document-trained, and undifferentiated. Don't build the core on them.

### 3.4 Restoration & enhancement (the value-add PhotoScan never had)

Users' end goal is a *restored image*, not a faithful scan of a faded print.

- **Fade/color-cast correction** (the flagship "faithful" feature): dye fading in chromogenic prints is a global, physically-structured shift — a white-balance/color-constancy problem, not a generative one. Afifi-style post-capture WB nets (CVPR 2019/2020) are tiny, on-device, non-hallucinatory, and produce dramatic before/afters.
- **Scratch/dust/crease removal:** detection remains the weak link (train our own); **LaMa** (Apache-2.0, ~27M params, ONNX/CoreML-convertible) for on-device erase; diffusion inpainting (BrushNet-class) in the cloud tier.
- **Descreening:** halftone prints (newspapers, yearbooks) have dedicated solutions — ICCV 2019 vintage-halftone restoration; **DESCAN-18K** dataset + DescanDiffusion (AAAI 2024). Auto-detect via FFT dot-pattern signature; PhotoScan ignored this entire category.
- **Face restoration:** **GFPGAN** (Apache-2.0) as commercial-safe baseline; CodeFormer is better on tiny faces but **non-commercial (S-Lab license)**. The 2025 frontier explicitly targets *honesty*: HonestFace, AuthFace, and **RestorerID** — which conditions restoration on *other photos of the same person*. For a family-archive app ("restore grandma's face using other photos of grandma"), that's a killer feature. Identity-drift metrics (ArcFace embedding distance) belong in QA.
- **Super-resolution:** Real-ESRGAN (BSD-3) on-device; **DiffBIR** (Apache-2.0) as the permissive diffusion-prior option; **OSEDiff** (Apache-2.0, one-step diffusion, ~0.1s/image on A100) **already ships on the OPPO Find X8** — generative enhancement at interactive latency, commercially licensed, proven on phone silicon. SUPIR/StableSR are license-blocked (non-commercial).
- **Colorization:** DDColor (Apache-2.0) for one-tap auto color; diffusion-controlled colorization (text/stroke hints) as premium. **Always labeled as interpretive, never bundled into "restore."**
- **Hallucination is now a named research problem.** The product consequence: a default **archival mode** restricted to non-generative ops (geometry, glare, descreen, denoise, color), an opt-in **enhance mode** for generative work, C2PA content credentials distinguishing the two, and the untouched master scan always preserved.

### 3.5 On-device ML and camera platform (2026)

**Android:**
- **Runtimes:** LiteRT NPU acceleration went GA (Jan 2026): NPU "up to 100× faster than CPU, 10× faster than GPU," zero-copy `AHardwareBuffer` → TensorBuffer camera input, vendor NPU delivery via Play "AI Packs." ExecuTorch 1.0 (Oct 2025) exports PyTorch research checkpoints straight to phones — ideal for prototyping.
- **Measured budgets:** Real-ESRGAN-x4 (1.2M params): 0.68ms on Snapdragon 8 Elite Gen 5, 2.45ms on mid-range 7 Gen 4. 5–20M-param CNNs land in tens of ms on NPU. Preview-loop guidance models fit 33ms frames on mid-range GPUs. Full 12MP multi-frame fusion: ~3s (DRIFT precedent). INT8 quantization: 2–4× speedup, <1% accuracy loss.
- **Camera:** CameraX 1.5 (RAW/DNG, Feature Groups, ZSL) + Camera2 interop for AE/AWB lock and true `captureBurst`.
- **Pose:** ARCore is alive (1.5x releases through 2026); its VIO provides 6-DoF pose priors for registration. Abstract the pose provider (ARCore → Jetpack XR migration path; rotation-vector sensor fusion fallback).

**iOS (researched July 2026 — materially different capture design required):**
- **Full-res RAW bursts do not exist on iOS.** Bracketed capture is capped at ~3–5 frames and *overrides* manual AE/AWB locks; 48MP ProRAW takes seconds per shot; 24MP output is itself a multi-frame Photonic-Engine fusion. The viable multi-frame path is **sequential 12MP captures** under the iOS 17+ responsive-capture stack (zero shutter lag, overlapping captures via `isResponsiveCaptureEnabled`, prepared photo settings), or a locked-exposure 4K video stream — with an optional single 24/48MP "detail anchor" frame. The iOS capture module diverges structurally from Android's and must be specified separately from day one.
- **Avoid iOS 17 deferred photo processing** in the scan flow: it routes final processing through the user's Photo Library and never returns the full-quality buffer to the app. Use `.balanced`/`.speed` with direct `AVCapturePhoto` delivery.
- **AE/AWB/AF fully lockable** (`setExposureModeCustom`, `setWhiteBalanceModeLocked`), and per-frame **camera intrinsics delivery** (`isCameraIntrinsicMatrixDeliveryEnabled`) is uniform across devices — a registration-prior advantage Android can't match uniformly. ARKit's `captureHighResolutionFrame()` returns pose-tagged 12MP stills mid-session (but no ProRAW/48MP during an ARSession).
- **ANE inference is a strength:** 1–20M-param vision models run in 1–40ms on A16–A19; Photoroom's ~10M-param production segmentation runs 27 FPS on A17 Pro. Mature porting path: coremltools 9.0 (torch.export, INT8/palettization) or ExecuTorch's Core ML backend. Vision framework provides free document segmentation at camera rate, lens-smudge detection (iOS 26), and structured document OCR (iOS 26) for photo-back capture.
- **Memory/thermal ceilings:** ~2.1GB jetsam limit on 4GB iPhones (~6.1GB on 16/17 Pro Max) — process bursts at 12MP, tile larger work, pool IOSurface-backed buffers; drive a thermal-degradation ladder off `ProcessInfo.thermalState`.
- **Model delivery:** Background Assets with Apple-Hosted asset packs (iOS 26; 200GB hosting included; On-Demand Resources is deprecated) — symmetric with Play AI Packs.

**Cross-platform:** no production KMP camera/pixel pipelines exist; the proven pattern (Snap's Djinni-bridged C++ core, Photoroom's PhotoGraph engine) is **native capture + native inference runtimes + a shared C++ core** for fusion/geometry/quality-gating math. KMP is fine for orchestration/state only.

**Privacy-credible cloud offload:** confidential-computing GPUs (H100/H200 TEEs, ~1–7% overhead) with remote attestation — the Google Private AI Compute / Apple PCC pattern. Send only the fused image, never the raw burst.

### 3.6 Capture UX state of the art

- **Auto-capture with live quality gates is table stakes** (ML Kit Doc Scanner, Mitek MiSnap): no shutter taps; fire when quality gates pass. ⚠ But *which* gates matter legally: Capital One's family (US10609293B2 → US12136266B2, to ~2038) covers glare-parameter-threshold-gated auto-capture with glare-reduction feedback. Gate on **focus/stability/framing**, not a glare threshold (§6).
- **Coverage/progress feedback** (Apple Object Capture's "capture dial"; IntelliCap ISMAR 2025): show accumulating capture progress rather than directing movement.
- **Live WYSIWYG fused preview** (Live HDR+ 2019, Adobe Project Indigo 2025, Scaniverse): progressively composite the glare-free result in the viewfinder. The user's mental model becomes *"sweep until the preview looks clean."*
- **Ghost-overlay alignment for ordinary users is proven** (Pixel 9 "Add Me").
- **Batch-first archival workflows** (Photomyne album pages; Epson FastFoto's 1 photo/sec and automatic back-side capture): continuous capture queue, background fusion, batch review.
- **Back-of-photo metadata:** handwriting OCR is commercially mature (and free on-device via iOS 26 `RecognizeDocumentsRequest`); visual date-estimation models exist. Scan the back, OCR "1962, Grandma at the lake," set EXIF date with a confirm step.
- **Accessibility:** the same real-time state machine, exposed as spoken prompts + haptics (Seeing AI / iOS 18 Magnifier patterns), makes the app usable eyes-free at near-zero marginal cost. (Audio guidance phrasing needs doctrine-of-equivalents review — §11.)

### 3.7 Color fidelity and physical scale (the substance behind "archival")

The standards flatbeds are judged by are public and numeric — **FADGI 3rd ed. (2023)**, **ISO 19264-1:2021**, **Metamorfoze** — and they are device-agnostic: compliance is demonstrated by imaging a physical target (DICE/GoldenThread) and scoring it in software (**OpenDICE** is free). FADGI 4-star (preservation grade) for prints: mean ΔE2000 ≤3, max ≤6, noise <2.0 levels, ≥600 ppi with claimed-vs-actual within ±6 ppi. **No published smartphone FADGI/ISO 19264 evaluation exists — being first, with honest numbers, is a positioning asset.**

What the evidence supports:

- **Phone sensors are colorimetrically adequate** (Sensitivity Metamerism Index ~86, comparable to DSLRs; mean ΔE2000 ~1.2–1.3 achievable from RAW with a ColorChecker-derived matrix under known light). The bottleneck is the *unknown room illuminant* and the vendor's "pleasing" render, not silicon.
- **The colorimetric backbone is free:** DNG dual-illuminant calibration metadata (ColorMatrix1/2, ForwardMatrix1/2, per-unit CalibrationTransform) is exposed by Android Camera2 and embedded in Apple ProRAW. Render colorimetrically (linear working space, no look table, no S-curve) — never derive the archival file from the tone-mapped HEIC/JPEG.
- **On-device illuminant estimation is solved to ~2° angular error at megabyte scale:** C5 (ICCV 2021, ~2MB, ~7ms GPU) and CCMNet (2025, ~1MB — consumes the same DNG CCMs as a camera fingerprint). Verify licenses before shipping.
- **Print scanning has two calibration anchors generic photography lacks:** (a) *paper-white borders* — near-neutral by manufacture (use as a prior with yellowing-aware confidence, not absolute truth); (b) *flash-dominant capture* — the torch is a factory-known illuminant; the ICCP 2016 flash/no-flash closed-form gives per-pixel white balance under arbitrary mixed lighting.
- **Radiometric consistency rules for fusion:** lock AE/AWB/focus across the fused burst; keep every fused stack in a *single illumination state* (all-torch or all-ambient); flash frames are per-pixel flash/ambient mixtures — use flash/ambient pairs for illuminant estimation, never naively min-composite them with ambient frames.
- **Absolute physical size is unrecoverable from a single unaided image** — only aspect ratio is. Honest DPI requires: quad + intrinsics → true aspect ratio; coarse absolute scale from ARKit/ARCore anchors (sub-cm published accuracy, though under-validated at 20–40cm print distance) or monocular metric depth (Apple Depth Pro, open source) as a prior; **snap to standard print sizes** (3.5×5, 4×6, 5×7, 8×10) with UI confirmation; reference-object/target override. Then write real `XResolution`/`YResolution`/`ResolutionUnit` — scanner-style metadata that downstream genealogy/archive tools actually consume. Photomyne exports a meaningless default 72dpi; PhotoScan writes nothing. Nobody does this correctly.
- **Honest ceilings:** under low-CRI narrow-band LEDs, metameric failure is uncorrectable in software (detect it and prompt for flash/daylight); the a* (red-green) axis is systematically weakest on phones; and 12MP over a 4×6 print is ~750 ppi *sampling* but handheld blur/demosaic cut delivered SFR/MTF well below that. Claims must be **measured, per-scan numbers** — "up to N ppi measured," not "600-DPI-equivalent."

**Product consequence — a two-tier honesty model:** default mode claims "accurate color" (realistic target-free performance: ΔE00 ~3–7, consistent with published smartphone colorimetry); an **archival mode** with a manufactured reference target in-frame (ColorChecker Passport, or GoldenThread object-level target which adds rulers for exact scale) builds a per-session profile and **reports measured ΔE per scan** — a proof mechanism flatbed software rarely surfaces, flipping our weakest claim into a differentiator. Only archival mode references FADGI star levels, phrased as "measured to X-star color/tone aims."

---

## 4. Product vision and differentiators

**One sentence:** *A phone app that produces measurably accurate, glare-free, full-resolution scans of printed photos at album-archive speed, processed on-device, with honest AI restoration — no subscription.*

The 2026 market's negative space, from review mining:

1. **Fidelity as the wedge — with receipts.** Full-sensor multi-frame fusion (12MP+, no artificial caps), minimal recompression, 16-bit TIFF/PNG/JPEG-XL export, ICC color management, *measured* per-scan ppi and (in archival mode) *measured* per-scan ΔE. Every incumbent fails this; we prove it instead of claiming it.
2. **The three fragmented jobs in one app:** (a) multi-frame glare-free capture (PhotoScan's abandoned strength), (b) album-page multi-photo batch detection (Photomyne's strength), (c) on-device AI restoration with faithful/reversible edits (Reimagine's promise, done honestly).
3. **Throughput.** Fast default path (single capture + learned de-glare) with a multi-frame sweep mode for glossy/glare cases; continuous album-page mode; background processing. Target 2–5s of *user time* per photo.
4. **Privacy as a headline claim.** "Your family photos never leave your phone." Differentiates against Photomyne (BIPA lawsuit history), Reimagine (cloud), and mail-in services; resonates with the older demographic doing family archives.
5. **Anti-subscription pricing.** Free unlimited capture; one-time purchase (~$20–40) for pro features. Subscription fatigue is the loudest complaint across all paid incumbents.
6. **Own what hardware can't do:** photos glued in albums, framed behind glass, oversized prints, photos at relatives' houses.
7. **Trust engineering:** archival vs. enhance modes, C2PA provenance, original always preserved, per-effect before/after toggles. Reviewers punish over-processing.

**Displaced-user acquisition channels live right now:** "Microsoft Lens alternative" and "PhotoScan won't install on Galaxy S25" searches.

---

## 5. Proposed system architecture

### 5.1 Pipeline overview

```
┌─────────────────────────────────────────────────────────────────────┐
│ CAPTURE (live loop, 30 FPS on preview frames)                       │
│  Android: CameraX 1.5 + Camera2 interop (RAW/DNG, AE/AWB lock, ZSL) │
│  iOS: AVFoundation sequential 12MP responsive captures (+ optional  │
│    24/48MP detail anchor); per-frame intrinsics; direct delivery    │
│  Pose priors: ARCore VIO / ARKit ARFrames (abstracted provider)     │
│  Guidance nets (NPU, zero-copy): corner-keypoint CNN (~96-224px)    │
│  FREEHAND SWEEP capture (patent-constrained design, §6):            │
│    continuous frames during a slow sweep; auto-capture gated on     │
│    focus/stability/framing (NOT glare thresholds)                   │
│  Single illumination state per fused stack (all-torch OR ambient)   │
│  Output: 5-15 frames + poses + intrinsics                           │
├─────────────────────────────────────────────────────────────────────┤
│ ALIGN (per burst, seconds, on-device — shared C++ core)             │
│  XFeat / open-SuperPoint+LightGlue matches                          │
│  → MAGSAC++ homography (planar prior; glare = outliers)             │
│  → dense residual flow for curl (NeuFlow-v2-class / mesh refine)    │
│  Pose + intrinsics priors seed and sanity-check the solve           │
├─────────────────────────────────────────────────────────────────────┤
│ FUSE (linear RAW domain where available)                            │
│  Handheld-MFSR kernel-regression merge (IPOL 2023 reference impl)   │
│  + robust outlier-rejecting temporal weights (glare-suppressing)    │
│  = joint demosaic + denoise + super-resolve + de-glare              │
│  Colorimetric path: DNG dual-illuminant matrices + illuminant       │
│  fusion (C5/CCMNet-class net + paper-white prior + flash anchor)    │
├─────────────────────────────────────────────────────────────────────┤
│ RESIDUAL DE-GLARE (on-device)                                       │
│  ~2.5-5M-param net (Adobe RAW-reflection recipe), trained on        │
│  PBR-synthesized glossy-print renders; handles sheen that never     │
│  leaves a region across frames; flagged, never silent.              │
│  Also serves as the single-shot fast path for matte prints.         │
├─────────────────────────────────────────────────────────────────────┤
│ BOUNDARY + RECTIFY                                                  │
│  Instance segmentation (SAM-distilled, multi-photo album pages)     │
│  → full-res sub-pixel corner refinement                             │
│  → homography + Zhang-He aspect-ratio recovery                      │
│  → physical size: AR scale / metric-depth prior → snap to standard  │
│    print sizes (UI confirm) → real DPI                              │
│  → optional dewarp for curled prints (UVDoc-class, once per capture)│
├─────────────────────────────────────────────────────────────────────┤
│ RESTORE (tiered, opt-in, non-destructive)                           │
│  Faithful (on-device, default): fade/WB correction, denoise,        │
│    descreen (FFT-detected), scratch removal (own detector + LaMa),  │
│    Real-ESRGAN-class SR                                             │
│  Enhance (opt-in, flagship NPU or cloud TEE): one-step diffusion    │
│    (OSEDiff-recipe distilled from WindowSeat/DAI teachers),         │
│    GFPGAN faces + fidelity slider, reference-conditioned faces      │
│  Creative (opt-in, labeled): DDColor colorization                   │
├─────────────────────────────────────────────────────────────────────┤
│ EXPORT                                                              │
│  Archival master: 16-bit TIFF, embedded ICC (Adobe RGB/eciRGB),     │
│    measured XResolution/YResolution, FADGI-style metadata, XMP      │
│    provenance (illuminant estimate, calibration state, measured ΔE) │
│  Derivatives: JPEG-XL / HEIF / JPEG (P3 or sRGB)                    │
│  C2PA credentials (corrected vs generatively-enhanced);             │
│  master + edit recipe always retained; cloud-agnostic export        │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Design principles

- **Physics first, learning second.** Multi-view fusion recovers true content; learned models handle residuals and single-shot fallbacks. Never let a generative model silently touch an archival output.
- **Linear RAW everywhere possible.** Glare photometry, fusion math, and color science are all better before tone curves. (iOS: 12MP RAW/YUV sequential frames; Android: DNG bursts; graceful YUV fallback.)
- **Adaptive effort by escalation, not instruction.** Fast single-shot path by default; the multi-frame sweep is a *mode the user chooses* (or is offered post-hoc when the result shows glare) — not a glare-triggered instruction loop (§6).
- **Every heavy model runs once per capture; only tiny models run per preview frame.**
- **Two-tier compute:** everything through faithful restoration on-device; diffusion-grade enhancement opt-in on flagship NPUs or attested TEE cloud, receiving only the fused image.
- **Shared C++ core** for fusion/geometry/quality-gating; native capture and inference per platform (§7).

---

## 6. Capture UX design (patent-constrained)

**The constraint (detail in §11):** Google patented both obvious capture flows — the fixed corner-dot guided composite (US10257485B2/US10531061B2, to ~Nov–Dec 2036) *and* detect-glare→instruct-camera-movement→composite (US10412316B2/US11050948B2, to ~2036–2037). Adaptive glare-driven capture targets — the "obvious modernization" — make exposure *worse*, walking into the second family plus Idemia's glare-token patent (US11200414B2) and Capital One's glare-threshold auto-capture family (to ~2038). The fusion math itself (min/median composite) is only claimed in dependent claims; **the capture UX, not the algorithm, is where infringement is determined.**

**The design consequence — lead with the flows that are clean:**

1. **Primary: freehand sweep.** "Hold the phone over the photo and slowly sweep." Continuous capture during the sweep; frames auto-selected on **focus/stability/framing/novel-baseline** gates (not a glare-parameter threshold); robust/learned fusion of 5–15 frames. No on-screen movement instructions tied to detected glare; no border-anchored target objects during capture. Natural hand motion plus a sweep supplies both the large-baseline glare diversity and the sub-pixel jitter that multi-frame SR wants (Samsung's WACV 2023 work shows handshake alone carries reflection-motion cues).
2. **Fast path: single-shot + learned de-glare.** One capture, learned residual de-glare (Adobe-recipe model). Implicates none of the guided multi-capture claims. Default for matte prints and batch throughput; the app may *offer a re-scan with the sweep mode* if the result shows residual glare (post-hoc offer ≠ capture-time movement instruction — confirm with counsel).
3. **Flash mode: flash/ambient pair fusion** built on the *expired* Microsoft flash/no-flash patent (US7457477B2, expired July 2024), avoiding Google Family C's claimed combination (glare-driven pose UI / glare-driven flash brightness + ambient-based color correction).
4. **Live fused preview** ("sweep until the preview is clean"): progressively composite the result in the viewfinder with a coverage/progress gauge. This gives users the *feedback* value of guidance without directive movement instructions. **Attorney questions before freeze:** does a residual-glare heatmap overlay constitute "instructions corresponding to moving the camera"? Does the detected-photo outline during capture read on US10531061B2's "object positioned within the edges"? Does spoken/haptic guidance trigger doctrine-of-equivalents?
5. **Batch flow:** continuous queue, background fusion, batch review screen, multi-photo album pages, voice notes.
6. **Back-of-photo capture** with OCR-prefilled metadata (front/back stored as a linked unit).
7. **Eyes-free mode:** state machine → spoken prompts + haptics (pending the DoE review above).
8. **Manual escape hatches everywhere:** corner adjustment, tap-to-resegment, glare mode toggle.

**Explicitly deferred pending license/expiry/counsel:** PhotoScan-style corner-dot choreography; glare-detection-triggered movement guidance; glare-threshold-gated auto-capture. If Google's maintenance fees lapse (windows 2026–2028) or a license is obtained, these unlock (§11).

---

## 7. Technology and platform choices

| Decision | Choice | Rationale |
|---|---|---|
| Platform (v1) | **Android-first, native Kotlin UI/capture** | CameraX 1.5 + Camera2 interop for RAW bursts; Android is where PhotoScan is literally uninstallable today |
| iOS (v2) | **Separate native capture module** (AVFoundation sequential 12MP responsive captures, direct delivery, AE/AWB/AF locks, per-frame intrinsics; ARKit pose); Core ML/ANE inference via coremltools 9.x or ExecuTorch Core ML backend | RAW bursts don't exist on iOS; brackets cap at ~3–5 and override manual locks; deferred processing bypasses the app. iOS demographic is critical (4.8★ demand signal) |
| Shared code | **C++ imaging core** (fusion, geometry, glare compositing, quality gates, thermal ladder) bridged per platform (Djinni-style); KMP only for orchestration/state if desired | Snap + Photoroom precedent; no production KMP pixel pipelines exist; capture layers cannot be shared |
| Inference runtime | **LiteRT** on Android (ML Drift GPU universal path; NPU via CompiledModel + Play AI Packs); **Core ML** on iOS (`computeUnits=.all`, FP16/INT8, ANE-resident ops validated early); ExecuTorch for prototyping both | Production-GA NPU stacks; zero-copy camera→model on both platforms |
| Quantization | INT8/w8a8 (Android), FP16→INT8 where A17+ (iOS) | 2–4× speedup, <1% loss; validate per-SoC on Qualcomm AI Hub / per-chip with MLModelBenchmarker |
| Min requirements | Android: minSdk 31, arm64-only for ML. iOS: 17+ (responsive capture stack); reduced preview models on A13/A14 | Required by LiteRT NPU/PODAI and iOS capture APIs |
| Classical CV | OpenCV (MAGSAC++, cornerSubPix, ECC) | Solved, cheap, license-clean |
| Merge core | Port of Handheld-MFSR (IPOL 2023 reference) with robust glare-suppressing weights | Peer-reviewed, open, joint demosaic+denoise+SR |
| Color pipeline | DNG dual-illuminant matrices + colorimetric render path + C5/CCMNet-class illuminant net + paper-white/flash anchors | §3.7; the substance behind archival claims |
| Model delivery | Play AI Packs (Android) / Apple-Hosted Background Assets (iOS 26) | Symmetric large-weight delivery, decoupled from app releases |
| Memory/thermal | 12MP working resolution for bursts; tiled 24/48MP; pooled buffers; thermal-degradation ladder in shared core | iOS jetsam ~2.1GB (4GB devices) – ~6.1GB (Pro Max); `thermalState` throttling |
| Cloud tier | Confidential-computing GPUs (H100/H200 TEE), remote attestation, ephemeral processing | The 2026-credible privacy architecture; fused image only |
| Maintenance posture | Track targetSdk/iOS SDK aggressively | The incumbent died of neglect, not competition |

---

## 8. Data strategy

Data, not architecture, is the accuracy lever (Genius Scan 51%→85%; Photomyne ~92% — both attribute gains to data).

**Reusable public assets:** OpenRR-5k / RRW / DRR (reflections), DocHR14K / SHDocs / SHIQ (document & specular highlights), DESCAN-18K (scan artifacts), Doc3D / UVDoc pipeline (dewarping, open capture tooling), BurstSR methodology (paired burst + ground truth), HDR+ dataset.

**Assets we must build (also defensible moats):**

1. **Glossy-print glare dataset & benchmark.** None exists publicly. Protocol: real prints (glossy/satin/matte, bordered/borderless, aged) captured as phone bursts under controlled glare (room light, lamp, flash) + flatbed ground truth of the same prints. Both necessary and publishable.
2. **Synthetic PBR training pipeline:** Blender Principled-BSDF renders of photo paper (varying gloss, curl, halftone, fading) under area lights and flash — the WindowSeat/Adobe recipe adapted to prints; composed in linear RAW space.
3. **Photo-boundary dataset:** prints with white/deckled borders, album sleeves, overlapping photos, textured backgrounds; synthetic composites + collected real captures.
4. **Scratch/dust detection masks:** the repeatedly-cited weak link in restoration; BOPBTL's dataset is small and license-encumbered.
5. **Aged-paper spectra prior:** paper-white anchoring needs a yellowing model across print stocks (chromogenic vs silver-gelatin vs inkjet) — no published dataset addresses this.
6. **Teacher-student distillation:** WindowSeat (Apache-2.0) and DAI as de-glare teachers; RoMa v2 as alignment teacher — the exact NTIRE 2026 winning recipe.

---

## 9. Evaluation strategy

- **Primary:** the printed-photo benchmark above, scored against flatbed ground truth.
- **FADGI/ISO 19264 self-scoring:** image DICE/GoldenThread targets with the app on each flagship device class, score with OpenDICE, publish per-metric star levels (color, tone, noise, SFR, sampling). Nobody has published smartphone results against these standards — first-mover honesty is stronger positioning than an unsubstantiated "600-DPI" headline.
- **Perceptual, not PSNR:** NTIRE 2026's own organizers note PSNR fails to rank mid-tier outputs. Gate releases on blinded human preference (cleanliness 0.25 / artifacts 0.25 / overall 0.5) plus LPIPS/MUSIQ/CLIP-IQA.
- **Honesty metrics for faces:** ArcFace identity distance + landmark fidelity (HonestFace protocol). Regression-test that "restore" never changes who someone is.
- **Geometry & scale:** masked AD/AAD metrics for boundary-incomplete cases; aspect-ratio error vs. known print sizes; DPI claimed-vs-measured within FADGI's ±6 ppi.
- **Competitive bake-off:** ours vs. PhotoScan vs. Photomyne vs. Reimagine vs. Epson FastFoto on the same physical prints — doubles as launch marketing material.
- **Performance:** per-SoC latency budgets via Qualcomm AI Hub / Core ML benchmarking; preview loop ≤33ms on mid-range hardware; full fusion ≤5s on 2023+ flagships, graceful degradation below; instrumented thermal soak tests.
- **Field truth:** app-store review mining of incumbents established the defect taxonomy (§2.2); track the same taxonomy in our own reviews.

---

## 10. Licensing audit

| Status | Components |
|---|---|
| ✅ Safe (Apache-2.0 / MIT / BSD) | LightGlue, XFeat, Glue-Factory open-SuperPoint, RoMa/RoMa v2, RAFT/SEA-RAFT, OpenCV, SAM/SAM 2 + MobileSAM/SlimSAM, DocRes, LaMa, Real-ESRGAN, SwinIR, GFPGAN, DDColor, OSEDiff, DiffBIR, MAXIM, WindowSeat weights, HDR+/MFSR IPOL reimplementations, Apple Depth Pro, coremltools (BSD-3), ExecuTorch (BSD-3) |
| ❌ Blocked without a deal | CodeFormer, StableSR (S-Lab non-commercial), SUPIR (explicit NC), FLUX.1-dev weights, MASt3R/DUSt3R (CC BY-NC-SA), original SuperPoint/SuperGlue weights, Ultralytics YOLO (AGPL-3.0) |
| ⚠ Unclear — verify or retrain | BOPBTL (MIT license vs "academic use only" README), BIPNet/Burstormer weights, RDNet, DAI, Lei flash-cues repo ("TBD"), EdgeSAM (S-Lab), DocTr++/DocScanner/UVDoc/DewarpNet checkpoints, HonestFace/OSDFace, **C5 / CCMNet** (illuminant estimation — license unconfirmed) |

Rules: no ⚠/❌ weights ship in the product; architectures are freely reimplementable and our synthetic-data pipeline makes retraining viable; audit every checkpoint before it enters the training *or* serving path (distillation from an NC teacher is a legal gray zone — get counsel's opinion).

---

## 11. Risks and open questions

Ranked by how much they could change the plan:

1. **Patent freedom-to-operate (existential — now mapped; formal counsel review still required).** Research-grade claim analysis (July 2026) found:
   - **Tier 1 (core-mechanism blockers):** Google Family B, US10257485B2/US10531061B2 + EP3610452B1/EP4358531B1 (the corner-dot guided composite of a physical photo, claimed nearly element-for-element; to ~Nov–Dec 2036 — and Google was still prosecuting an EP divisional in 2024 despite abandoning the app). Google Family A, US10412316B2/US11050948B2 + EP/CN/JP/KR counterparts (detect-obstruction → instruct camera movement → min/median composite; to ~2036–2037; inventors are the SIGGRAPH 2015 authors).
   - **Tier 2 (feature-level):** Capital One US10609293B2→US12136266B2 + pending continuation (glare-threshold auto-capture with corrective feedback, to ~2038); Idemia US11200414B2 et al. (glare-located capture tokens on ID documents, to ~2039); Google Family C US10675955B2/US11483463B2 (flash-mode glare removal combination, to ~2038).
   - **Tier 3 (monitor):** Photomyne US10452905B2 (album segmentation; claim 1 needs WLAN transmission — on-device processing avoids it; check continuations); Mitek/USAA families (check-deposit-specific, expiring ~2028–2032, but a nine-figure-verdict enforcement climate).
   - **Clean routes:** unguided freehand sweep (no movement instructions, no border-anchored targets, no glare-detection gate); single-shot neural de-glare; flash/no-flash on expired US7457477B2; min/median compositing per se (dependent claims only). "Learned fusion instead of min-composite" is **not** a safe harbor — the composite limitation reads on neural fusion too.
   - **Validity/monitoring leverage:** the inventors' own Aug 2015 SIGGRAPH paper is citable prior art against the EP obstruction patent (no grace period in Europe); PhotoScan is abandoned while fees are still being paid — **watch US maintenance-fee windows 2026–2028**; a lapse of Family B would unlock the corner-dot UX early. Consider quietly exploring a Google license/covenant — it would convert the Tier-1 risk into a moat.
   - **Actions:** attorney claim charts of both Google families *before the capture UX freezes* (Phase 0 gate); attorney answers to the §6 questions (glare heatmap display, detected-quad outline, audio guidance DoE); patent watches (Capital One continuation, EP divisional line, Adobe/Kee and Apple filings publishing through 2027); build the defensive prior-art file (SIGGRAPH 2015, CVPR 2000 layer extraction, Farid–Adelson 1999, Levin–Weiss 2004, Agrawal SIGGRAPH 2005, Kodak US5974199, Microsoft flash/no-flash).
2. **iOS capture divergence (now researched; residual unknowns are empirical).** The Android RAW-burst design has no iOS equivalent (§3.5) — the plan now specifies sequential 12MP responsive captures with a shared C++ core. Remaining unknowns requiring a 1–2 week device spike: sustained shot-to-shot rate at 12MP with locked AE/AWB on iPhone 13/15/17; `maxBracketedCapturePhotoCount` on current hardware; ARKit↔AVCaptureSession coexistence/handoff latency; ANE residency of our specific ops after conversion; thermal soak behavior.
3. **Colorimetric accuracy & DPI claims (now substantiated; wording matters).** The two-tier honesty model (§3.7) makes claims defensible: target-free = "accurate color" (ΔE00 ~3–7), archival mode = per-scan measured ΔE and measured ppi against FADGI aims. Remaining work: in-house OpenDICE scoring per device class; C5/CCMNet license verification; AR-scale accuracy validation at 20–40cm capture distance; aged-paper-white reliability study; whether genealogy platforms preserve embedded ICC/resolution metadata on upload.
4. **Sherlocking.** Google or Apple could absorb scanning into Photos at any time. Defensibility = pipeline quality + archival trust + batch/metadata workflows, not the capture gimmick alone.
5. **NPU operator coverage.** Vendor compilers may not support deformable convs/attention at full res; validate every architecture on Qualcomm AI Hub / Core ML early (cheap to check early, expensive late).
6. **Effective-resolution honesty.** Multi-frame SR gains on halftone/film-grain content are unproven (burst-SR models train on natural scenes). Our benchmark must measure *delivered* SFR/MTF (slanted-edge), not sensor megapixels — and marketing must quote measured numbers.
7. **Cloud-tier economics.** TEE-GPU inference cost/cold-start at consumer scale is unquantified; the tier is opt-in and paid, which bounds exposure.
8. **Distillation-from-NC-teachers legality** (see §10).

---

## 12. Phased roadmap

### Phase 0 — Feasibility spikes & legal gates (6–10 weeks)
- **FTO gate:** attorney claim charts of Google Families A & B + the §6 UX questions. No capture-UX freeze until this lands. Start maintenance-fee watches and the prior-art file.
- Port the IPOL Handheld-MFSR merge; feed it a manually-captured DNG burst of a glossy print; add robust glare-suppressing weights. **Go/no-go: fused output visibly beats PhotoScan on the same print.**
- Android capture prototype: CameraX 1.5 RAW burst with AE/AWB lock + ARCore pose logging on 2–3 reference devices (flagship + mid-range).
- **iOS capture spike (1–2 weeks):** measure sequential-capture rates, bracket limits, ARKit/AVCapture handoff, ANE latencies for candidate models on A16/A19 devices.
- **Color spike:** DNG-matrix colorimetric render path + OpenDICE scoring of a GoldenThread target on 2 devices; validate AR scale at print distances.
- Alignment harness (XFeat+LightGlue+MAGSAC++); NPU operator-coverage checks (Qualcomm AI Hub + coremltools) for candidate fusion/de-glare architectures.
- Build v0 of the print benchmark (50 prints, phone bursts + flatbed ground truth).

### Phase 1 — Capture core MVP (3–4 months)
- Freehand-sweep capture (per §6) with live boundary tracking and focus/stability-gated auto-capture; single-shot fast path.
- Align → RAW fuse → residual de-glare v0 (trained on synthetic PBR prints) → boundary → rectify (aspect-ratio recovery + sub-pixel corners) → physical-size estimation + DPI metadata.
- Export: JPEG + 16-bit TIFF, ICC color-managed, measured-DPI-tagged; master retention.
- Internal bake-off vs. PhotoScan/Photomyne on the benchmark; OpenDICE per-device scoring.

### Phase 2 — The differentiators (3–4 months)
- Live fused preview + coverage gauge (as cleared by counsel); flash/ambient mode.
- Multi-photo album-page batch mode with background processing queue.
- Faithful restoration tier: fade/WB correction (four-signal illuminant fusion), descreen, scratch detect+LaMa, Real-ESRGAN.
- Back-of-photo capture + handwriting OCR metadata; accessibility (eyes-free) mode.
- Archival mode with reference-target profiling and per-scan measured ΔE reports.
- Public beta; publish the benchmark + FADGI results + a technical blog post (review-bait and hiring-bait).

### Phase 3 — Enhance tier & expansion (ongoing)
- One-step diffusion enhance tier: on-device on flagship NPUs, TEE-cloud elsewhere (opt-in, C2PA-labeled).
- Face restoration with fidelity slider; reference-conditioned family faces.
- Colorization (creative, labeled). iOS app on the shared C++ core.
- Expansion candidates: negatives/slides via backlit capture (FilmBox's ~2MP output is a low bar), framed-photo/behind-glass mode, genealogy-service exports.
- Re-evaluate capture UX options if Google patents lapse or a license lands.

---

## 13. Key references

**Original system**
- PhotoScan glare removal — Google Research blog (2017): https://research.google/blog/photoscan-taking-glare-free-pictures-of-pictures/
- Obstruction-Free Photography (SIGGRAPH 2015): https://sites.google.com/site/obstructionfreephotography/

**Patents (freedom-to-operate)**
- Google Family A (obstruction-guided capture): https://patents.google.com/patent/US10412316B2/en · https://patents.google.com/patent/US11050948B2/en
- Google Family B (corner-dot composite): https://patents.google.com/patent/US10257485B2/en · https://patents.google.com/patent/US10531061B2/en
- Google Family C (flash glare mode): https://patents.google.com/patent/US11483463B2/en
- Capital One (glare-gated auto-capture): https://patents.google.com/patent/US12136266B2/en
- Idemia (glare tokens): https://patents.google.com/patent/US11200414B2/en
- Photomyne (album segmentation): https://patents.google.com/patent/US10452905B2/en
- Expired Microsoft flash/no-flash: https://patents.google.com/patent/US7457477B2/en

**Reflection/glare**
- Adobe, *Removing Reflections from RAW Photos* (2024): https://arxiv.org/abs/2404.14414
- RDNet (CVPR 2025): https://arxiv.org/abs/2410.08063 · NTIRE 2026 report: https://arxiv.org/abs/2604.10321
- WindowSeat (Apache-2.0): https://huggingface.co/huawei-bayerlab/windowseat-reflection-removal-v1-0
- Dereflection Any Image (AAAI 2026): https://arxiv.org/abs/2503.17347
- Neural Spline Fields (CVPR 2024): https://light.princeton.edu/publication/nsf/
- Flash-only cues (CVPR 2021): https://github.com/ChenyangLEI/flash-reflection-removal
- DocHR14K (2025): https://arxiv.org/abs/2504.14238 · SHDocs (NeurIPS 2024)

**Burst fusion / alignment / SR**
- Handheld Multi-Frame Super-Resolution (SIGGRAPH 2019): https://sites.google.com/view/handheld-super-res/ · IPOL 2023 implementation: http://www.ipol.im/pub/art/2023/460/
- HDR+ (SIGGRAPH Asia 2016) + IPOL 2021 implementation: https://www.ipol.im/pub/art/2021/336/
- NTIRE 2025 Efficient Burst HDR: https://arxiv.org/html/2505.12089v1 · DRIFT: https://arxiv.org/html/2604.03402
- LightGlue: https://github.com/cvg/LightGlue · XFeat: https://github.com/verlab/accelerated_features · SEA-RAFT: https://github.com/princeton-vl/SEA-RAFT · RoMa v2: https://github.com/Parskatt/romav2
- MAGSAC++ in OpenCV: https://docs.opencv.org/4.x/de/d3e/tutorial_usac.html

**Boundary / rectification**
- Genius Scan detection retrospective: https://blog.thegrizzlylabs.com/2024/10/document-detection.html
- DocRes (CVPR 2024, MIT): https://github.com/ZZZHANG-jx/DocRes · UVDoc: https://github.com/tanguymagne/UVDoc · DocTr++: https://github.com/fh2019ustc/DocTr-Plus
- MobileSAM/EdgeTAM lineage survey: https://arxiv.org/html/2410.04960v1
- Zhang–He aspect-ratio recovery: https://www.sciencedirect.com/science/article/abs/pii/S1051200406000595

**Restoration**
- Bringing Old Photos Back to Life (CVPR 2020): https://github.com/microsoft/bringing-old-photos-back-to-life
- LaMa: https://github.com/advimman/lama · DESCAN-18K (AAAI 2024): https://arxiv.org/abs/2402.05350
- GFPGAN: https://github.com/TencentARC/GFPGAN · HonestFace (2025): https://arxiv.org/abs/2505.18469 · RestorerID: https://arxiv.org/abs/2411.14125
- OSEDiff (NeurIPS 2024, ships on OPPO Find X8): https://github.com/cswry/OSEDiff · DiffBIR: https://github.com/XPixelGroup/DiffBIR · DDColor: https://github.com/piddnad/DDColor

**Color fidelity & physical scale**
- FADGI Technical Guidelines, 3rd ed. (2023): https://www.digitizationguidelines.gov/guidelines/digitize-technical.html
- ISO 19264-1:2021: https://www.iso.org/standard/79172.html
- DNG/DCP color model: https://www.cobalt-image.com/tutorials/our_technology/ · DCamProf: https://torger.se/anders/dcamprof.html
- C5 cross-camera color constancy (ICCV 2021): https://arxiv.org/abs/2011.11890 · CCMNet (2025): https://arxiv.org/html/2504.07959v1
- Flash white balance under mixed illumination (ICCP 2016): http://imagesci.ece.cmu.edu/files/paper/2016/whitebalance_iccp16.pdf
- Apple Depth Pro (metric monodepth): https://github.com/apple/ml-depth-pro
- FADGI TIFF metadata guidelines: https://www.digitizationguidelines.gov/guidelines/TIFF_Metadata_Final.pdf

**Platform**
- CameraX 1.5: https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html
- LiteRT NPU GA: https://developers.googleblog.com/litert-the-universal-framework-for-on-device-ai/
- ExecuTorch 1.0: https://pytorch.org/blog/introducing-executorch-1-0/
- iOS responsive/deferred capture (WWDC23): https://developer.apple.com/videos/play/wwdc2023/10105/ · High-res capture (WWDC26): https://developer.apple.com/videos/play/wwdc2026/304/
- coremltools 9: https://github.com/apple/coremltools/releases · ARKit high-res frames: https://developer.apple.com/documentation/arkit/arsession/3975720-capturehighresolutionframe
- Vision document segmentation: https://developer.apple.com/documentation/vision/vndetectdocumentsegmentationrequest
- Snap Djinni (shared C++ core precedent): https://eng.snap.com/improving_djinni · Photoroom PhotoGraph: https://www.photoroom.com/inside-photoroom/building-cross-platform-image-renderer
- Qualcomm AI Hub: https://aihub.qualcomm.com/
- Google Private AI Compute: https://blog.google/innovation-and-ai/products/google-private-ai-compute/

**UX & competitive**
- Apple Object Capture (WWDC23): https://developer.apple.com/videos/play/wwdc2023/10191/
- IntelliCap (ISMAR 2025): https://arxiv.org/abs/2508.13043 · Adobe Project Indigo: https://research.adobe.com/articles/indigo/indigo.html
- ML Kit Document Scanner: https://developers.google.com/ml-kit/vision/doc-scanner
- Microsoft Lens retirement: https://support.microsoft.com/en-us/topic/retirement-of-microsoft-lens-fc965de7-499d-4d38-aeae-f6e48271652d
