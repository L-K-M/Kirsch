# PLAN.md — A 2026 successor to Google PhotoScan

**Project codename: Kirsch** *(after the Kirsch edge-detection operator — fitting for an app whose first job is finding the edges of a photograph)*

This document synthesizes a research pass (July 2026) over the imaging-science literature, platform capabilities, and competitive landscape relevant to building a modern version of Google PhotoScan: a phone app that digitizes printed photos by capturing multiple views, fusing them to remove glare, then cropping, rectifying, and enhancing the result.

---

## Table of contents

1. [Why now — the opportunity](#1-why-now--the-opportunity)
2. [How the original PhotoScan works, and where it falls short](#2-how-the-original-photoscan-works-and-where-it-falls-short)
3. [What has changed since 2016 — research survey](#3-what-has-changed-since-2016--research-survey)
4. [Product vision and differentiators](#4-product-vision-and-differentiators)
5. [Proposed system architecture](#5-proposed-system-architecture)
6. [Capture UX design](#6-capture-ux-design)
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
- **No metadata workflow.** No back-of-photo capture, no date/caption OCR, JPEG-only output, no color management, no DPI tagging, tight coupling to Google Photos backup.

### 2.3 What to keep

The **physics is still right in 2026**: moving the camera displaces specular highlights, and a robust multi-frame composite recovers *true* content rather than hallucinating it. Multi-view fusion remains the most artifact-free, trustworthy glare-removal signal for glossy prints. We keep the principle and modernize everything around it.

---

## 3. What has changed since 2016 — research survey

### 3.1 Reflection & glare removal

Three generations of progress:

- **Task-specific CNNs (2019–2023):** ERRNet, IBCLN, location-aware SIRR, DSRNet — released code, now clearly outperformed. Useful only as baselines.
- **Transformer/prior models (2024–2025):** **RDNet** (CVPR 2025) won NTIRE 2025 single-image reflection removal and underpins all three NTIRE 2026 winners. The current best open *deterministic* (non-generative) remover.
- **One-step diffusion (2025–2026):** **Dereflection Any Image** (AAAI 2026) and Huawei's **WindowSeat** (Apache-2.0 weights, CVPR 2026 NTIRE) define the quality ceiling; both were used as *distillation teachers* by NTIRE 2026 winners. Multi-GB, server-class — but distillable.
- **The mobile existence proof:** Adobe's *Removing Reflections from RAW Photos* (arXiv 2404.14414) — a **2.5M-parameter** model trained on physically-synthesized linear-RAW mixtures, running in **~1 second on an iPhone 14 Pro**, shipped in Camera Raw/Lightroom (Dec 2024). Near-SOTA quality fits in a few MB if you (a) work in RAW/linear space, (b) invest in physically accurate synthetic training data.
- **Print-specific literature now exists:** glossy-print glare is closer to *document specular-highlight removal* (broad sheen on paper) than to window reflections. Datasets: **DocHR14K** (14,902 real pairs, 2025), **SHDocs** (NeurIPS 2024), SHIQ (2021).
- **Multi-frame methods matured:** learned obstruction removal (CVPR 2020), Samsung's burst Reflection-Motion-Aggregation cues from handshake alone (WACV 2023), **Neural Spline Fields** (CVPR 2024) — test-time optimization over an unstabilized RAW burst that jointly super-resolves and separates layers (a "max quality" cloud-tier candidate).
- **Flash/no-flash cues:** flash-RAW-minus-ambient-RAW gives a physically reflection-free guide image (CVPR 2021); Flash-Split (CVPR 2025) makes it robust to handheld misalignment. Phones have flashes; a scanning app controls capture timing. Caveat: flash speculars on high-gloss paper saturate — fire flash on oblique corner shots so flash glare also moves between frames.
- **Polarization:** works in the lab (PolarFree, CVPR 2025) but **no consumer phone has a polarized RGB sensor** — do not build the product around it; optionally support clip-on CPL as a pro accessory.
- **Industry signal:** OPPO ships on-device AI reflection removal (<4s); OnePlus/OPPO-class NPUs run eraser-grade models locally. Nobody combines this with a guided multi-shot print-scanning UX. That's our lane.

### 3.2 Burst fusion, alignment, and super-resolution

- **Google's own lineage is now open:** HDR+ (SIGGRAPH Asia 2016) and **Handheld Multi-Frame Super-Resolution** (SIGGRAPH 2019, the Pixel's Super Res Zoom) both have peer-reviewed open reimplementations (IPOL 2021/2023). Handheld MFSR fuses a RAW burst via anisotropic kernel regression with per-pixel robustness masks — **simultaneously demosaicing, denoising, and super-resolving**. Its robustness-mask machinery is exactly where a glare/outlier term plugs in. This is the gold-standard merge core for a scanner: the 5+ frame aligned burst PhotoScan already captures is precisely the input multi-frame SR needs. The resolution cap becomes resolution *upside*.
- **Deep burst SR** (DBSR → BIPNet → Burstormer → 2024-26 Mamba/one-step-diffusion variants): NTIRE 2025's efficiency track proved a **3.3M-param align-then-restore network runs in ~64ms** (0.28 TFLOPs); DRIFT (2026) runs an 11-frame 12MP NAFNet burst pipeline in **3.2s on a Snapdragon 8 Elite NPU**. Plain NAFNet-class CNNs beat exotic architectures for mobile.
- **Alignment is essentially solved for planar targets:** XFeat (CPU real-time, Apache-2.0) or open-retrained SuperPoint + **LightGlue** (Apache-2.0) matches → OpenCV **MAGSAC++** (`USAC_MAGSAC`) homography — near-failure-free, milliseconds, glare blobs simply become outliers. Dense residual flow for page curl: **SEA-RAFT** (BSD-3) / NeuFlow v2 (>20 FPS on Jetson-class hardware) replace the 2016 grid-flow approximation. **RoMa v2** (MIT) serves as an offline teacher/cloud fallback, and its per-pixel certainty doubles as merge weight maps.
- **Platform unblocked:** CameraX 1.5 (Nov 2025) exposes **RAW/DNG capture** (`OUTPUT_FORMAT_RAW_JPEG`); Camera2 interop provides AE/AWB lock and `captureBurst`. Merging in linear RAW — impossible for a third-party app in 2016 — is now the right default.

### 3.3 Boundary detection, cropping, rectification

- **Industry-proven recipe:** a tiny (<10MB, MobileNet-class) corner-keypoint CNN at ~96–224px for 25–30 FPS live tracking, IMU-fused Kalman smoothing, then a heavier capture-time pass + classical sub-pixel refinement. Genius Scan documented detection accuracy going 51% → 85% almost entirely via *data* (synthetic hard cases + refinement net). Data, not architecture, is the lever.
- **Segment Anything family:** MobileSAM / EdgeSAM / SlimSAM (~5–10M params) make promptable, arbitrary-background segmentation on-device viable; EdgeTAM hits 16 FPS on an iPhone 15 Pro Max. Enables tap-to-fix corrections and **multi-photo album-page splitting** (Photomyne's headline feature, ~92% accuracy — plan a manual-edit fallback).
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

### 3.5 On-device ML and camera platform (Android, 2026)

- **Runtimes:** LiteRT NPU acceleration went GA (Jan 2026): NPU "up to 100× faster than CPU, 10× faster than GPU," zero-copy `AHardwareBuffer` → TensorBuffer camera input, vendor NPU delivery via Play "AI Packs." ExecuTorch 1.0 (Oct 2025) exports PyTorch research checkpoints straight to phones — ideal for prototyping.
- **Measured budgets:** Real-ESRGAN-x4 (1.2M params): 0.68ms on Snapdragon 8 Elite Gen 5, 2.45ms on mid-range 7 Gen 4. 5–20M-param CNNs land in tens of ms on NPU. Preview-loop guidance models fit 33ms frames on mid-range GPUs. Full 12MP multi-frame fusion: ~3s (DRIFT precedent). INT8 quantization: 2–4× speedup, <1% accuracy loss.
- **Camera:** CameraX 1.5 (RAW/DNG, Feature Groups, ZSL) + Camera2 interop for AE/AWB lock and true `captureBurst`. Flutter's camera stack still lacks RAW — build the capture path native.
- **Pose:** ARCore is alive (1.5x releases through 2026) and its VIO gives the 6-DoF pose stream to place world-anchored capture targets, enforce parallax baselines between shots, and auto-trigger capture at target poses. Abstract the pose provider (ARCore → Jetpack XR migration path; rotation-vector sensor fusion fallback).
- **Privacy-credible cloud offload:** confidential-computing GPUs (H100/H200 TEEs, ~1–7% overhead) with remote attestation — the Google Private AI Compute / Apple PCC pattern. Send only the fused image, never the raw burst.

### 3.6 Capture UX state of the art

- **Auto-capture with live quality gates is table stakes** (ML Kit Doc Scanner, Mitek MiSnap): no shutter taps; fire when alignment/steadiness/exposure pass.
- **Adaptive coverage guidance** (Apple Object Capture's "capture dial"; IntelliCap ISMAR 2025 next-best-view): replace the fixed 4-dot ritual with targets placed *where glare actually persists* — a matte print in diffuse light needs 1 shot; a glossy print under a lamp needs 3–6 targeted off-axis shots.
- **Live WYSIWYG fused preview** (Live HDR+ 2019, Adobe Project Indigo 2025, Scaniverse): progressively composite the glare-free result in the viewfinder with a residual-glare heatmap. The user's mental model becomes *"clean the preview,"* not *"chase dots."*
- **Ghost-overlay alignment for ordinary users is proven** (Pixel 9 "Add Me").
- **Batch-first archival workflows** (Photomyne album pages; Epson FastFoto's 1 photo/sec and automatic back-side capture): continuous capture queue, background fusion, batch review.
- **Back-of-photo metadata:** handwriting OCR is commercially mature; visual date-estimation models exist. Scan the back, OCR "1962, Grandma at the lake," set EXIF date with a confirm step.
- **Accessibility:** the same real-time alignment/glare/coverage state machine, exposed as spoken prompts + haptics (Seeing AI / iOS 18 Magnifier patterns), makes the app usable eyes-free at near-zero marginal cost.
- **⚠ Patent caution:** Capital One holds a family (US10609293 + continuations) around real-time glare detection in a document-masked region with corrective instructions and threshold-gated auto-capture; Google holds patents around glare-free capture and burst merging (e.g., US11678068). A freedom-to-operate review is required before finalizing interaction mechanics (see §11).

---

## 4. Product vision and differentiators

**One sentence:** *A phone app that produces flatbed-quality, glare-free, color-faithful scans of printed photos at album-archive speed, processed on-device, with honest AI restoration — no subscription.*

The 2026 market's negative space, from review mining:

1. **Fidelity as the wedge.** Full-sensor multi-frame fusion output (12MP+ toward 600-DPI-equivalent for 4×6 prints), no artificial caps, minimal recompression, TIFF/PNG/JPEG-XL export, color-managed, DPI-tagged. Every incumbent fails this, and it's cheaply verifiable by reviewers against a flatbed.
2. **The three fragmented jobs in one app:** (a) multi-frame glare-free capture (PhotoScan's abandoned strength), (b) album-page multi-photo batch detection (Photomyne's strength), (c) on-device AI restoration with faithful/reversible edits (Reimagine's promise, done honestly).
3. **Throughput.** Fast default path (single capture + learned de-glare) with automatic escalation to multi-frame only when glare is detected; continuous album-page mode; background processing. Target 2–5s of *user time* per photo.
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
│  CameraX 1.5 + Camera2 interop (RAW/DNG, AE/AWB lock, ZSL)          │
│  ARCore 6-DoF pose → world-anchored capture targets                 │
│  Guidance nets (NPU, zero-copy): corner-keypoint CNN (~96-224px)    │
│    + specular-highlight detector → residual-glare heatmap           │
│  Auto-capture on {aligned, steady, exposed, novel-baseline} gates   │
│  Output: 5-15 RAW frames + poses + per-frame glare maps             │
├─────────────────────────────────────────────────────────────────────┤
│ ALIGN (per burst, seconds, on-device)                               │
│  XFeat / open-SuperPoint+LightGlue matches                          │
│  → MAGSAC++ homography (planar prior; glare = outliers)             │
│  → dense residual flow for curl (NeuFlow-v2-class / mesh refine)    │
├─────────────────────────────────────────────────────────────────────┤
│ FUSE (linear RAW domain)                                            │
│  Handheld-MFSR kernel-regression merge (IPOL 2023 reference impl)   │
│  + soft-min glare term + learned glare-confidence weight maps       │
│  = joint demosaic + denoise + super-resolve + de-glare              │
│  Fallback (non-RAW devices): YUV-domain merge                       │
├─────────────────────────────────────────────────────────────────────┤
│ RESIDUAL DE-GLARE (on-device)                                       │
│  ~2.5-5M-param net (Adobe RAW-reflection recipe), trained on        │
│  PBR-synthesized glossy-print renders; handles sheen that never     │
│  leaves a region across frames; flagged, never silent               │
├─────────────────────────────────────────────────────────────────────┤
│ BOUNDARY + RECTIFY                                                  │
│  Instance segmentation (SAM-distilled, multi-photo album pages)     │
│  → full-res sub-pixel corner refinement                             │
│  → homography + Zhang-He aspect-ratio recovery + print-size priors  │
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
│  TIFF / PNG / JPEG-XL / HEIF; ICC color-managed; DPI-tagged;        │
│  C2PA credentials (corrected vs generatively-enhanced);             │
│  master scan + edit recipe always retained; cloud-agnostic export   │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Design principles

- **Physics first, learning second.** Multi-view fusion recovers true content; learned models handle residuals and single-shot fallbacks. Never let a generative model silently touch an archival output.
- **Linear RAW everywhere possible.** Glare photometry, fusion math, and color science are all better before tone curves. This is Adobe's proven recipe and was impossible for third-party apps in 2016.
- **Adaptive effort.** Escalate from 1 shot → N shots → cloud tier based on detected glare/curl/damage, not ritual.
- **Every heavy model runs once per capture; only tiny models run per preview frame.**
- **Two-tier compute:** everything through faithful restoration on-device (privacy, offline, free); diffusion-grade enhancement opt-in on flagship NPUs or attested TEE cloud, receiving only the fused image.

---

## 6. Capture UX design

1. **Point at photo.** Live corner detection + IMU-Kalman tracking snaps a boundary overlay; multi-photo album pages get per-photo boundaries.
2. **First frame auto-captures** when gates pass (steady, exposed, in-frame). Zero shutter taps from here on.
3. **Glare-driven guidance:** the specular detector paints a residual-glare heatmap; world-anchored targets (ARCore) appear only where glare persists, placed to guarantee parallax baseline. Matte print in soft light → done in one shot. Glossy print under a ceiling lamp → 3–6 targets.
4. **Live fused preview:** the viewfinder progressively shows the accumulating glare-free composite (Object-Capture-style coverage dial for outstanding regions). Done = clean preview.
5. **Flip prompt (optional):** capture the back; on-device handwriting OCR pre-fills date/caption/people metadata with one-tap confirm; front/back stored as a linked unit.
6. **Batch flow:** continuous queue, fusion runs in background, batch review screen afterwards; voice notes per photo.
7. **Eyes-free mode:** the same state machine drives spoken prompts + haptics ("move left… hold still… captured, 2 of 5"); full TalkBack/VoiceOver labeling.
8. **Manual escape hatches everywhere:** corner adjustment, boundary re-segmentation by tap (promptable SAM-distilled model), glare-removal off toggle.

---

## 7. Technology and platform choices

| Decision | Choice | Rationale |
|---|---|---|
| Platform (v1) | **Android-first, native Kotlin** | Capture core needs CameraX 1.5 + Camera2 interop; Flutter camera lacks RAW/burst; Android is where PhotoScan is literally uninstallable today |
| iOS (v2) | **Kotlin Multiplatform** for pipeline orchestration/logic; thin Swift/AVFoundation capture module | ~60%+ code reuse without bridging the camera path; iOS demographic skews heavily toward the family-archive market (see §11 gap) |
| Inference runtime | **LiteRT** (ML Drift GPU universal path; NPU via CompiledModel + Play AI Packs for Qualcomm/MediaTek); **ExecuTorch** for research-checkpoint prototyping | Production-GA NPU stack; zero-copy camera→model; one artifact, per-SoC delivery |
| Quantization | INT8/w8a8 for all shipping models | 2–4× speedup, <1% loss; validate per-SoC on Qualcomm AI Hub device farm early |
| Min requirements | minSdk 31, arm64-only for ML features | Required by LiteRT NPU/PODAI anyway |
| Classical CV | OpenCV (MAGSAC++, cornerSubPix, ECC) | Solved, cheap, license-clean |
| Merge core | Port of Handheld-MFSR (IPOL 2023 reference) with glare-aware robustness masks | Peer-reviewed, open, joint demosaic+denoise+SR |
| Cloud tier | Confidential-computing GPUs (H100/H200 TEE), remote attestation, ephemeral processing | The 2026-credible privacy architecture; send fused image only |
| Maintenance posture | Track targetSdk aggressively | The incumbent died of neglect, not competition |

---

## 8. Data strategy

Data, not architecture, is the accuracy lever (Genius Scan 51%→85%; Photomyne ~92% — both attribute gains to data).

**Reusable public assets:** OpenRR-5k / RRW / DRR (reflections), DocHR14K / SHDocs / SHIQ (document & specular highlights), DESCAN-18K (scan artifacts), Doc3D / UVDoc pipeline (dewarping, open capture tooling), BurstSR methodology (paired burst + ground truth), HDR+ dataset.

**Assets we must build (also defensible moats):**

1. **Glossy-print glare dataset & benchmark.** None exists publicly. Protocol: real prints (glossy/satin/matte, bordered/borderless, aged) captured as phone bursts under controlled glare (room light, lamp, flash) + flatbed ground truth of the same prints. Both necessary and publishable.
2. **Synthetic PBR training pipeline:** Blender Principled-BSDF renders of photo paper (varying gloss, curl, halftone, fading) under area lights and flash — the WindowSeat/Adobe recipe adapted to prints; composed in linear RAW space.
3. **Photo-boundary dataset:** prints with white/deckled borders, album sleeves, overlapping photos, textured backgrounds; synthetic composites + collected real captures.
4. **Scratch/dust detection masks:** the repeatedly-cited weak link in restoration; BOPBTL's dataset is small and license-encumbered.
5. **Teacher-student distillation:** WindowSeat (Apache-2.0) and DAI as de-glare teachers; RoMa v2 as alignment teacher — the exact NTIRE 2026 winning recipe.

---

## 9. Evaluation strategy

- **Primary:** the printed-photo benchmark above, scored against flatbed ground truth.
- **Perceptual, not PSNR:** NTIRE 2026's own organizers note PSNR fails to rank mid-tier outputs. Gate releases on blinded human preference (cleanliness 0.25 / artifacts 0.25 / overall 0.5) plus LPIPS/MUSIQ/CLIP-IQA.
- **Honesty metrics for faces:** ArcFace identity distance + landmark fidelity (HonestFace protocol). Regression-test that "restore" never changes who someone is.
- **Geometry:** masked AD/AAD metrics (2025 rectification literature) for boundary-incomplete cases; aspect-ratio error vs. known print sizes.
- **Competitive bake-off:** ours vs. PhotoScan vs. Photomyne vs. Reimagine vs. Epson FastFoto on the same physical prints — this doubles as launch marketing material.
- **Performance:** per-SoC latency budgets via Qualcomm AI Hub; preview loop ≤33ms on mid-range GPUs; full fusion ≤5s on 2023+ flagships, graceful degradation below.
- **Field truth:** app-store review mining of incumbents established the defect taxonomy (§2.2); track the same taxonomy in our own reviews.

---

## 10. Licensing audit

| Status | Components |
|---|---|
| ✅ Safe (Apache-2.0 / MIT / BSD) | LightGlue, XFeat, Glue-Factory open-SuperPoint, RoMa/RoMa v2, RAFT/SEA-RAFT, OpenCV, SAM/SAM 2 + MobileSAM/SlimSAM, DocRes, LaMa, Real-ESRGAN, SwinIR, GFPGAN, DDColor, OSEDiff, DiffBIR, MAXIM, WindowSeat weights, HDR+/MFSR IPOL reimplementations |
| ❌ Blocked without a deal | CodeFormer, StableSR (S-Lab non-commercial), SUPIR (explicit NC), FLUX.1-dev weights, MASt3R/DUSt3R (CC BY-NC-SA), original SuperPoint/SuperGlue weights, Ultralytics YOLO (AGPL-3.0) |
| ⚠ Unclear — verify or retrain | BOPBTL (MIT license vs "academic use only" README), BIPNet/Burstormer weights, RDNet, DAI, Lei flash-cues repo ("TBD"), EdgeSAM (S-Lab), DocTr++/DocScanner/UVDoc/DewarpNet checkpoints, HonestFace/OSDFace |

Rules: no ⚠/❌ weights ship in the product; architectures are freely reimplementable and our synthetic-data pipeline makes retraining viable; audit every checkpoint before it enters the training *or* serving path (distillation from an NC teacher is a legal gray zone — get counsel's opinion).

---

## 11. Risks and open questions

Ranked by how much they could change the plan:

1. **Patent freedom-to-operate (existential; unresolved).** Google/MIT patents may cover the SIGGRAPH 2015 obstruction-free work and the shipped PhotoScan mechanism (guided multi-shot capture to displace speculars + robust min-compositing); Capital One's US10609293 family covers real-time glare feedback + threshold-gated auto-capture; Google holds burst-merge/HDR+ patents (e.g., US11678068). Mitigations: commission a professional FTO search **before Phase 1 ends**; note that 2015–2017-filed patents begin expiring 2035+ (so design-arounds, not waiting, are the strategy); document design-around options (e.g., different guidance mechanics, learned fusion rather than soft-min).
2. **iOS capture/inference stack under-researched.** The family-archive demographic skews iPhone (PhotoScan's 4.8★ iOS rating is the demand signal), but AVFoundation ProRAW burst semantics, AE/AWB lock, ANE latency for our model sizes, and background-processing limits were not covered in this research pass. **Action: dedicated research spike before committing to KMP boundaries** (Phase 0).
3. **Colorimetric accuracy & DPI claims need substantiation.** "Flatbed-competitive, 600-DPI-equivalent, archival" implies capture-time color science: illuminant estimation, DNG color matrices/camera profiling, ICC output, and physical print-size estimation (ARCore metric pose + known-print-size priors get partway; a pocketable color/size reference card is a possible pro accessory). Flatbeds meet FADGI/ISO digitization standards; reviewers will check. **Action: color-science spike (Phase 0); soften public claims until measured.**
4. **Sherlocking.** Google or Apple could absorb scanning into Photos at any time. Defensibility = pipeline quality + archival trust + batch/metadata workflows, not the capture gimmick alone.
5. **NPU operator coverage.** Vendor compilers may not support deformable convs/attention at full res; validate every architecture on Qualcomm AI Hub before training investment (cheap to check early, expensive late).
6. **Effective-resolution honesty.** Multi-frame SR gains on halftone/film-grain content are unproven (burst-SR models train on natural scenes at 4× zoom). Our benchmark must measure *effective* DPI (slanted-edge MTF), not sensor megapixels.
7. **Cloud-tier economics.** TEE-GPU inference cost/cold-start at consumer scale is unquantified; the tier is opt-in and paid, which bounds exposure.
8. **Distillation-from-NC-teachers legality** (see §10).

---

## 12. Phased roadmap

### Phase 0 — Feasibility spikes (6–10 weeks)
- Port the IPOL Handheld-MFSR merge; feed it a manually-captured DNG burst of a glossy print; add a soft-min glare term. **Go/no-go: fused output visibly beats PhotoScan on the same print.**
- CameraX 1.5 RAW burst prototype with AE/AWB lock + ARCore pose logging on 2–3 reference devices (flagship + mid-range).
- XFeat+LightGlue+MAGSAC++ alignment harness; measure on-device latency.
- NPU operator-coverage check for candidate fusion/de-glare architectures (Qualcomm AI Hub).
- **iOS stack research spike** (risk #2) and **color-science spike** (risk #3).
- **Commission FTO search** (risk #1).
- Build v0 of the print benchmark (50 prints, phone bursts + flatbed ground truth).

### Phase 1 — Capture core MVP (3–4 months)
- Guided capture with live corner tracking, auto-capture, fixed-pattern multi-shot (adaptive guidance comes later — ship the physics first).
- Align → RAW fuse → residual de-glare v0 (trained on synthetic PBR prints) → boundary → rectify (with aspect-ratio recovery + sub-pixel corners).
- Export: JPEG + TIFF, color-managed, DPI-tagged; master retention.
- Internal bake-off vs. PhotoScan/Photomyne on the benchmark.

### Phase 2 — The differentiators (3–4 months)
- Adaptive glare-driven guidance + live fused preview + coverage dial.
- Multi-photo album-page batch mode with background processing queue.
- Faithful restoration tier: fade/WB correction, descreen, scratch detect+LaMa, Real-ESRGAN.
- Back-of-photo capture + handwriting OCR metadata; accessibility (eyes-free) mode.
- Public beta; publish the benchmark + a technical blog post (review-bait and hiring-bait).

### Phase 3 — Enhance tier & expansion (ongoing)
- One-step diffusion enhance tier: on-device on flagship NPUs, TEE-cloud elsewhere (opt-in, C2PA-labeled).
- Face restoration with fidelity slider; reference-conditioned family faces.
- Colorization (creative, labeled). iOS app on the KMP core.
- Phase-2 expansion candidates: negatives/slides via backlit capture (FilmBox's ~2MP output is a low bar), framed-photo/behind-glass mode, genealogy-service exports.

---

## 13. Key references

**Original system**
- PhotoScan glare removal — Google Research blog (2017): https://research.google/blog/photoscan-taking-glare-free-pictures-of-pictures/
- Obstruction-Free Photography (SIGGRAPH 2015): https://sites.google.com/site/obstructionfreephotography/

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

**Platform**
- CameraX 1.5: https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html
- LiteRT NPU GA: https://developers.googleblog.com/litert-the-universal-framework-for-on-device-ai/
- ExecuTorch 1.0: https://pytorch.org/blog/introducing-executorch-1-0/
- Qualcomm AI Hub: https://aihub.qualcomm.com/
- Google Private AI Compute: https://blog.google/innovation-and-ai/products/google-private-ai-compute/

**UX & competitive**
- Apple Object Capture (WWDC23): https://developer.apple.com/videos/play/wwdc2023/10191/
- IntelliCap (ISMAR 2025): https://arxiv.org/abs/2508.13043 · Adobe Project Indigo: https://research.adobe.com/articles/indigo/indigo.html
- ML Kit Document Scanner: https://developers.google.com/ml-kit/vision/doc-scanner
- Capital One glare/auto-capture patent family: https://patents.google.com/patent/US10609293B2/en
- Microsoft Lens retirement: https://support.microsoft.com/en-us/topic/retirement-of-microsoft-lens-fc965de7-499d-4d38-aeae-f6e48271652d
