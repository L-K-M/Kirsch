# Kirsch: a validated plan for a modern PhotoScan successor

**Project codename:** Kirsch, after the Kirsch edge-detection operator.

**Validation date:** 2026-07-13

This plan proposes an Android app for digitizing printed photographs with multi-view glare removal, accurate geometry and color, full-resolution output, and optional restoration. It incorporates an independent review of the original proposal against primary product documentation, papers, platform APIs, standards, patent records, and software licenses.

This is an engineering research plan, not a claim that every cited technique transfers to glossy prints or runs efficiently on phones. Patent discussion is issue spotting, not legal advice.

## Contents

1. [Validation verdict](#1-validation-verdict)
2. [Product and incumbent](#2-product-and-incumbent)
3. [Research assessment](#3-research-assessment)
4. [Product contract](#4-product-contract)
5. [Phase 0 benchmark and gates](#5-phase-0-benchmark-and-gates)
6. [Candidate architecture](#6-candidate-architecture)
7. [Capture UX and patents](#7-capture-ux-and-patents)
8. [Data and model strategy](#8-data-and-model-strategy)
9. [Archival, color, and evaluation rules](#9-archival-color-and-evaluation-rules)
10. [Licensing controls](#10-licensing-controls)
11. [Roadmap](#11-roadmap)
12. [Open decisions and risks](#12-open-decisions-and-risks)
13. [Primary references](#13-primary-references)
- [Appendix A: product rationale and positioning hypotheses](#appendix-a-product-rationale-and-positioning-hypotheses-unvalidated)

## 1. Validation verdict

### 1.1 Conclusion

The concept is feasible enough to justify a focused prototype. The strongest part of the original proposal remains unchanged: moving the camera changes view-dependent specular glare, so registered multi-view observations can recover real image content rather than invent it. Modern RAW access, robust registration, efficient inference, and better evaluation tooling create a credible opportunity to improve on the 2017 PhotoScan pipeline.

The complete proposed stack is not yet validated. In particular, research on reflections through glass, document highlights, burst super-resolution, and document dewarping does not automatically transfer to glossy continuous-tone prints. The first investment must therefore be a print-specific benchmark and a conservative non-generative fusion prototype, not a large application or model-training program.

### 1.2 What survives review

- Keep multi-view capture as the quality path and a single-shot path as the speed baseline.
- Work in a linear RAW domain when the device and capture cadence permit it; retain a YUV fallback.
- Preserve original captures and separate faithful processing from generative enhancement.
- Build a glossy-print dataset with flatbed references and physical image-quality targets.
- Treat boundary detection, batch scanning, metadata capture, and reversible editing as meaningful product opportunities.
- Keep Android as the initial platform and keep the imaging core independent of Android UI code.

### 1.3 Material corrections to the earlier proposal

| Earlier assertion | Validated position |
|---|---|
| PhotoScan is abandonware that cannot be installed on modern Android devices | PhotoScan remains listed on Google Play, shows an Install action, and is linked from Google Photos. Its store listing was last updated on 2023-07-24. Device-specific failures may exist, but general unavailability was not established. Call it maintenance-only or stagnant, not unavailable. |
| PhotoScan dates from 2003 | It launched in November 2016. The user likely meant that it has not materially evolved for years. |
| A CameraX RAW path can use Camera2 interop to call `captureBurst` | CameraX supports RAW/DNG capture, but its `ImageCapture` requests are sequential and interop does not hand the app arbitrary capture-session ownership. A true minimum-time RAW burst needs a direct Camera2 module. |
| Adobe's 2.5M-parameter remover runs in about one second on an iPhone 14 Pro | The paper reports a 1K preview in 4.5 to 6.5 seconds on a MacBook or iPhone 14 Pro, with full-resolution upsampling as additional work. It targets reflections through glass, not print-surface glare. |
| Handheld MFSR is the proven merge core for this scanner | It is a valuable baseline. Its robust weights were not designed or validated for specular print glare, and the IPOL implementation is not a mobile production core. |
| FADGI four-star means mean Delta E00 <=3, max <=6, noise <2, and +/-6 ppi | Those values are incorrect. Section 9 records the applicable reflective-print aims and the limits of any conformance claim. |
| A session target provides measured Delta E for every subsequent scan | Delta E is measurable only on known target patches in the evaluated capture. It cannot reveal the unknown original color of arbitrary photograph pixels. |
| On-device processing clears the cited Photomyne patent | It may omit the WLAN limitation in one patent, but an active parent has claims without that limitation. The full family needs counsel review. |
| The capture UX, not fusion, determines infringement | The cited independent claims combine capture/detection limitations with composite or output generation. Every claim limitation matters; no proposed flow is pre-cleared. |
| All components in the former safe-license row are permissive and commercially usable | Several are not. Code, weights, base models, dependencies, datasets, and patents require separate artifact-level review. |

## 2. Product and incumbent

### 2.1 What PhotoScan does

Google's published flow captures one reference frame and four additional views. ORB descriptors on Harris corners estimate homographies, a coarse-grid optical-flow refinement compensates for non-planarity, and a soft minimum selects approximately the darkest registered observation while reducing boundary seams. A final stage detects the photograph boundary, rectifies the quadrilateral, and rotates the result.

The physics is still sound. Specular highlights often saturate the sensor, making a single observation unrecoverable. Camera motion moves that glare across the printed image so another frame may contain the missing signal.

### 2.2 Verified status as of the validation date

- The Google Play listing reports 50M+ downloads, a 3.9 rating from roughly 205K reviews, and an update date of 2023-07-24.
- The iOS listing remains public.
- Google Photos help explicitly routes users to the separate PhotoScan app.
- The app supports saving locally; Google Photos backup is optional.
- There is no public evidence that the core imaging method has materially changed since Google's 2017 technical explanation.

The opportunity is therefore a stagnant but still distributed incumbent, not an absent one. Kirsch must win a controlled quality and workflow comparison rather than rely on incompatibility as its premise.

### 2.3 Product hypotheses to test

The following are hypotheses, not established market facts:

- Users value materially higher delivered resolution and less compression.
- Large family archives make throughput, batch review, and metadata more important than a marginal quality gain.
- Users will pay once for a private, on-device workflow rather than accept a subscription.
- Back-of-photo capture, dates, captions, and album organization can differentiate the product.
- A trustworthy separation between faithful correction and generative enhancement matters to archivists and families.

Do not use commercial market-size reports, isolated reviews, or store ratings as TAM proof. Before pricing or positioning is frozen, run customer interviews and document review mining by store, country, date range, sample size, deduplication method, taxonomy, and counts.

Appendix A preserves the original proposal's fuller product rationale as explicitly unvalidated hypotheses and demand signals, to structure that interview and review-mining work.

### 2.4 Competitive baseline

| Product | Verified fact | Required Kirsch research |
|---|---|---|
| Google PhotoScan | Publicly listed; five-view glare-removal flow; Play listing last updated 2023-07-24 | Controlled captures and exported-file inspection on current devices |
| Microsoft Lens | Removed from stores on 2026-02-09; new scans disabled after 2026-03-09 | Determine whether displaced document-scanner users overlap with photo-scanning users |
| Photomyne | Active photo-scanning product with album workflows | Measure quality, throughput, output metadata, current pricing, and offline behavior |
| MyHeritage Reimagine | Active scanning and restoration product | Test processing location, reversibility, fidelity, and failure modes |
| Epson FastFoto FF-680W | Active high-speed feeder scanner | Use as a throughput and quality comparator, not a substitute for mounted/framed photos |

## 3. Research assessment

### 3.1 Model the right optical problem

Through-glass reflection removal commonly models an observed image as transmitted scene plus reflected scene. A glossy print is different: the camera sees diffuse print radiance plus a view- and light-dependent surface-specular term. Broad sheen may remain over the same region across all views, and saturated highlights contain no recoverable signal.

Consequences:

- Window-reflection networks such as RDNet, WindowSeat, and Adobe's RAW method are candidate baselines or teachers, not validated print removers.
- Document-highlight datasets such as DocHR14K are closer to the target domain, but text documents do not reproduce the textures, legitimate highlights, grain, fading, and halftone patterns of photographs.
- A temporal minimum can suppress glare but can also darken real highlights or select exposure/color inconsistencies.
- Dense flow may follow moving glare instead of print texture and create ghosting.
- Synthetic rendering is useful only after its transfer is measured on real held-out prints.

### 3.2 Capture and camera platform

CameraX introduced RAW/DNG and RAW+JPEG output in 1.5. It is suitable for preview, lifecycle integration, analysis, and ordinary single-frame capture. Repeated `ImageCapture.takePicture()` requests execute sequentially, however. The quality path should prototype a direct Camera2-owned session using burst requests, per-frame metadata, AE/AWB/AF locks, and explicit buffers.

Gate these capabilities independently rather than treating `RAW` as one boolean:

- `REQUEST_AVAILABLE_CAPABILITIES_RAW`
- usable burst cadence and supported stream combinations
- output-stall duration and buffer limits
- manual exposure/focus behavior
- RAW metadata completeness, including optional second illuminant matrices
- ARCore shared-camera coexistence, if pose priors prove useful
- sustained thermal and memory behavior across OEMs

ARCore can provide pose priors and viewpoint-diversity feedback. Its translation units being meters does not establish archival scale accuracy, and world coordinates can be revised as tracking improves. Do not derive authoritative physical dimensions or PPI from ARCore without validation.

### 3.3 Registration and conservative fusion

Use a hierarchy rather than committing to a research model up front:

1. Homography baseline: XFeat with its supported matcher/LighterGlue, or openly retrained SuperPoint with LightGlue, followed by OpenCV MAGSAC++.
2. Photometric normalization and robust temporal statistics in linear space.
3. Confidence masks for saturation, frame boundaries, registration residual, motion, and temporal inconsistency.
4. Mesh or flow residual alignment only when the homography residual justifies it.
5. Handheld MFSR as an experimental branch after glare-only fusion is understood.

Do not describe alignment as solved. Low-texture prints, dark areas, repeated patterns, glare-covered features, curl, and large viewpoint changes are explicit test strata. Compare XFeat + LighterGlue and open-SuperPoint + LightGlue on the actual benchmark before selecting either.

The first merge should not super-resolve. A conservative native-resolution output makes registration, glare suppression, and reconstruction failures easier to isolate. Add MFSR only if it improves independently measured SFR without increasing artifacts or changing legitimate print texture.

### 3.4 Boundary detection and geometry

A small corner/quad detector with classical full-resolution refinement is a plausible live pipeline. Promptable segmenters such as SAM variants do not by themselves discover multiple photo instances or stable quadrilaterals; album pages need an instance proposal/detection stage, segmentation, geometric fitting, and manual correction.

Document dewarping networks are candidates for curled prints, not established solutions. Test dewarp-before-fusion, dewarp-after-fusion, and no dewarp. Start the MVP with one mostly planar, fully visible photograph and manual corner correction.

Perspective rectification must preserve inferred aspect ratio. Snapping to common print sizes can be offered only as an explicitly confirmed estimate because prints may be trimmed or nonstandard.

### 3.5 Learned removal and restoration

Learned processing comes after the benchmark and classical baseline:

- A small print-specific residual de-glare model is justified only if conservative multi-view fusion leaves repeatable residual errors.
- Adobe's work strongly supports linear scene-referred training and physically informed synthesis, but not its latency or domain transfer to prints.
- White balance is a useful fading baseline, not a complete physical model. Dye layers can fade nonlinearly and spatially, and lost information cannot always be recovered.
- Descreening, denoising, inpainting, prior-driven super-resolution, face restoration, and colorization all alter image information. Some may be useful derivatives, but none belongs silently in an archival master.
- LaMa, Real-ESRGAN, GFPGAN, diffusion systems, and similar prior-driven models can synthesize content regardless of whether their architecture is called a GAN, CNN, or diffusion model.

## 4. Product contract

**Product sentence:** Kirsch produces high-resolution, glare-reduced digital copies of printed photographs on Android, preserves the original captures, and keeps corrective and generative edits explicit and reversible.

### 4.1 Output layers

Every scan is a small provenance package, not one silently overwritten JPEG:

1. **Acquisition record:** capture metadata, app/device version, calibration inputs, and the source DNG/YUV burst while retained. Source frames are immutable, but keeping every burst is opt-in: archival mode shows the storage estimate and retains or exports it; the default mode deletes source frames only after successful fusion and explicit scan acceptance.
2. **Base master candidate:** minimally processed, color-managed, target-bearing output where an archival target was captured. Any fusion is documented and must be validated before this is described as an archival master.
3. **Production derivative:** cropped/rectified and glare-reduced image for ordinary viewing and sharing.
4. **Restored derivative:** opt-in denoise, descreen, scratch removal, fade correction, or other content-changing operations with a saved recipe.
5. **Reconstructed derivative:** optional multi-frame super-resolution from measured subpixel observations, labeled with the method and scale.
6. **Generative derivative:** clearly labeled prior-driven super-resolution, face restoration, inpainting, or colorization.

FADGI disallows digital dust removal and color/tone restoration on photographic master images. A restored or generatively enhanced file is never the preservation master.

### 4.2 MVP promise

The MVP promises:

- one mostly planar and fully visible print at a time
- single-shot baseline plus optional unguided multi-view capture
- native-resolution conservative fusion
- automatic quad suggestion with manual corner correction
- local processing and local export
- preserved originals and reproducible edit parameters
- JPEG sharing output and 16-bits-per-channel TIFF production output through explicit native encoders

The MVP does not promise album splitting, dewarping, restoration, super-resolution, inferred physical PPI, FADGI conformance, cloud enhancement, or C2PA credentials.

## 5. Phase 0 benchmark and gates

### 5.1 Build the benchmark before the app

Start with at least 50 physical photographs, expanding after the capture protocol stabilizes. Stratify by:

- glossy, satin, matte, album sleeve, and framed/glass surfaces
- dark, bright, low-texture, repeated-pattern, face, and legitimate-highlight content
- white border, dark border, borderless, decorative edge, and damaged edge
- flat, curled, dog-eared, and partially lifted geometry
- chromogenic, inkjet, silver-gelatin, halftone, faded, and scratched examples where available
- ambient daylight, warm/cool LED, mixed light, area lamp, and phone flash/torch
- flagship and mid-range Android devices

For each print retain a high-quality flatbed capture, physical dimensions, print/surface description, lighting setup, phone RAW/YUV bursts, per-frame metadata, and manual masks for glare, saturation, print boundary, and known defects. A flatbed is a useful content reference, not automatically perfect ground truth; profile and evaluate it too.

Include independent physical targets for color, tone, scale, noise, and SFR. Keep profile-creation targets separate from verification targets so evaluation is not circular.

The first 50 prints are a protocol-development set, not automatic evidence of superiority. Use pilot variance to pre-register the confirmatory sample size, one primary endpoint and practical effect margin, held-out split, exclusions, and confidence-interval decision rule. Treat the physical print as the unit of analysis; repeated captures, lighting conditions, and devices are nested observations rather than independent samples.

### 5.2 Baselines

Run all methods on fixed, versioned inputs:

- best single phone frame
- current Google PhotoScan export
- registered per-pixel minimum
- registered soft minimum
- robust median/trimmed estimator
- conservative confidence-weighted temporal fusion
- IPOL Handheld MFSR unchanged
- selected learned single-image removers only as research baselines

### 5.3 Metrics

- Glare: recall on manually labeled glare and saturated regions, residual-glare area, and legitimate-highlight preservation.
- Registration: reprojection error, edge double-image/ghosting rate, and failure rate by content stratum.
- Fidelity: flatbed-referenced error where meaningful, target-patch Delta E00, tone response, noise, and blinded expert/user preference.
- Detail: SFR10/SFR50 and sharpening modulation on a physical target, reported separately from pixel dimensions.
- Geometry: corner error, aspect-ratio error, and reproduction-scale error only when a physical target or confirmed dimensions are present.
- Performance: capture cadence, end-to-end latency, peak memory, energy, and thermal degradation on named devices.
- Reliability: completion and retry rates in observed usability sessions.

Do not use PSNR or a no-reference aesthetic score as the sole release gate. They can reward smoothing or hallucinated detail.

### 5.4 Go/no-go gates

Set numerical thresholds before looking at final test results. At minimum:

- **Gate A, physics:** on the pre-registered held-out test, the print-level confidence interval for the primary endpoint must clear the practical superiority margin over PhotoScan and the best single frame, while pre-registered no-regression bounds pass for ghosting, color error, and lost legitimate highlights.
- **Gate B, capture:** direct Camera2 must deliver a usable RAW or YUV burst on the target device classes without unacceptable stalls, drops, memory pressure, or thermal collapse.
- **Gate C, runtime:** pre-register and pass per-device limits for post-capture latency, peak memory, energy, and thermal degradation, including a sustained batch soak rather than one cold run.
- **Gate D, workflow:** representative users must complete the unguided capture reliably; otherwise revise capture before building restoration features.
- **Gate E, legal:** counsel must chart the final capture/fusion flow against the relevant claims in target jurisdictions before UX freeze.
- **Gate F, licensing:** every code, model, weight, dataset, and training dependency in the selected path must have an artifact-level bill of materials and approved use.

If Gate A fails, stop the larger product plan and investigate capture geometry/lighting rather than hiding the failure with generative restoration.

## 6. Candidate architecture

This architecture is conditional on Phase 0 results.

```text
PREVIEW AND GUIDANCE
  CameraX current stable: preview, lifecycle, ImageAnalysis
  tiny print-quad model + focus/stability/framing checks
  optional ARCore or IMU pose prior, not a scale authority

CAPTURE
  single shot: CameraX RAW/JPEG where appropriate
  quality path: dedicated Camera2 RAW/YUV session
    captureBurstRequests, AE/AWB/AF lock, per-frame metadata
  capability-driven fallback: RAW burst -> YUV burst -> single shot

REGISTER
  XFeat + supported matcher/LighterGlue
    OR open-SuperPoint + LightGlue
  MAGSAC++ homography
  optional mesh/flow residual only when benchmarked confidence requires it

FUSE IN LINEAR SPACE
  black-level/exposure normalization
  saturation, boundary, residual, and temporal-consistency masks
  conservative robust temporal estimator at native resolution
  MFSR and learned residual removal remain experimental branches

RECTIFY
  print-specific quad detector
  full-resolution edge/corner refinement
  aspect-ratio-preserving homography
  manual correction always available

COLOR AND EXPORT
  feature-detected DNG calibration metadata
  explicit conversion into the chosen output color space
  libtiff/libpng or another audited native encoder for high-bit-depth output
  JPEG in sRGB for compatibility
  acquisition record + derivative graph + edit parameters
```

### 6.1 Implementation boundaries

- Native Kotlin application and capture state machine.
- C++ imaging core for RAW unpacking, registration, fusion, color, geometry, and native export.
- LiteRT or ExecuTorch only after exact candidate models are benchmarked on exact delegates and input sizes.
- `minSdk 31` and arm64-only may be sensible product constraints, but they are not LiteRT requirements. Select them from device-population and maintenance data.
- Set the current Google Play-required `targetSdk` at implementation time and review it before each release; do not hard-code a multi-year policy assumption into architecture.
- Treat zero-copy and INT8 gains as measured optimizations, not defaults. Buffer format conversion, unsupported operators, tiling, and delegate behavior can erase headline benchmark gains.

## 7. Capture UX and patents

### 7.1 Constraint

The identified Google patent families include combinations of obstruction or border processing, guided multi-image capture, and composite/output generation. Particular minimum/median filters appear in dependent claims, while composite/output generation also appears in independent claims. Photomyne's family includes claims relevant to detecting, enhancing, and cropping multiple photograph regions.

No prose analysis here establishes freedom to operate. Legal status shown by Google Patents is explicitly an assumption; official USPTO, EPO, and national records control. Claim scope, validity, prosecution history, jurisdiction, and the implemented product all matter.

### 7.2 Design-around candidates for counsel to chart

- Unguided freehand sweep with a screen-space progress indicator based on sharp-frame count and viewpoint diversity, not glare-triggered target poses.
- Single-shot capture with post-capture learned de-glare.
- Flash/ambient capture as a separate experimental method.
- Post-capture suggestion to retry in a user-selected multi-view mode.
- Manual shutter mode with no movement instruction.

These candidates are intended to omit identified limitations; they are not described as clean, safe, non-infringing, or public domain. A live glare map, quad overlay, spoken instruction, haptic guidance, progress metric, and learned fusion must all be included in the final claim charts.

The cited Microsoft US7457477B2 record shows lapse for failure to pay maintenance fees effective 2016-11-25, not expiration in July 2024. That fact concerns one US patent and does not clear flash/no-flash imaging generally or in other jurisdictions.

### 7.3 UX research

Automatic capture based on focus, stability, and framing is an established pattern, but the Kirsch interaction still requires testing. Coverage gauges and ghost overlays are precedents, not proof that users will perform the needed camera motion.

Do not cite Adobe Project Indigo as a shipped WYSIWYG fused preview; Adobe described that viewfinder behavior as future work. Back-of-photo handwriting also needs a camera-image HTR benchmark. ML Kit Digital Ink recognizes captured pen/finger strokes, not handwriting in camera photographs.

## 8. Data and model strategy

Public availability does not make a dataset reusable. Record license terms and source-image provenance before download or training.

### 8.1 Assets to build

1. Print-specific multi-view glare benchmark described in Section 5.
2. Boundary dataset with print borders, overlapping photos, album sleeves, textured backgrounds, and occlusion.
3. RAW/YUV device corpus covering target OEMs and capture fallbacks.
4. Synthetic glossy-print renderer varying BRDF, surface roughness, curl, light size/position, exposure, saturation, paper/ink texture, fading, and sensor response.
5. Defect masks for scratches, dust, creases, and halftone only if restoration advances past the MVP.

### 8.2 Training order

1. Establish non-learned capture, registration, and fusion baselines.
2. Split real physical prints by print identity and source collection before training; never leak alternate captures of the same print across splits.
3. Calibrate synthetic-to-real transfer on a validation set without touching the final physical-print test set.
4. Train a small residual model only against repeatable baseline failures.
5. Distill only from teachers whose exact code, weights, base models, and usage rights are approved.
6. Quantize only after floating-point quality is established; report model-specific loss by device and delegate.

## 9. Archival, color, and evaluation rules

### 9.1 FADGI facts

FADGI Third Edition evaluates a controlled digitization system and workflow, not a phone model in isolation or an arbitrary photograph without a target. Relevant four-star aims for prints and photographs include:

| Metric | Four-star aim |
|---|---:|
| Sampling frequency | At least 594 ppi for a nominal 600 ppi capture |
| Bit depth | 16 bits per channel |
| Master format | TIFF or JPEG 2000 |
| Reproduction-scale error | Less than +/-1% |
| Mean color error | Delta E00 <2 |
| 90th-percentile color error | Delta E00 <4 |
| White balance | Delta E(a*b*) <2 |
| Tone response | Delta L00 <1.5 for each gray patch |
| Lightness non-uniformity | <1% |
| Noise upper limit | L* standard deviation <1 |
| SFR10 sampling efficiency | >90% |
| SFR50 | >45% and <65% of half-sampling frequency |
| Color-channel misregistration | <0.33 pixel |
| Maximum sharpening modulation | <1.02 |

OpenDICE is appropriate for off-device qualification using supported targets and reference files. It is not an Android runtime library. Publish individual measured metrics and say that they meet selected FADGI four-star metric aims only when the complete procedure supports that statement. Do not call the app or an unqualified scan FADGI-compliant.

### 9.2 Color rules

- RAW sensor values need black-level correction, demosaic, white balance, camera-to-reference-space conversion, and an explicit output transform. Embedding a profile without converting pixels into that profile is wrong.
- Android/DNG second illuminant and corresponding `*2` matrices are optional. Feature-detect them and use a valid single calibration when necessary.
- Phone flash/torch is controlled but not a factory-known colorimetric illuminant. Android exposes availability and relative strength, not calibrated spectral power. Characterize it per device/level or estimate it from a target/pair.
- A paper-white border is a useful uncertain prior, not a neutral truth; age and stock can shift it materially.
- A session profile characterizes the capture setup. Report Delta E only on independent known verification patches in that capture, never as the unknown photograph's color error.
- A 24-patch ColorChecker can support checks but is not sufficient for a robust custom ICC profile under the cited FADGI guidance. Use a separately characterized, sufficiently sampled profiling target.
- Learned illuminant estimation is a Phase 2 candidate for target-free color, not an MVP dependency. Compact cross-camera models exist (C5, ICCV 2021, roughly 2 MB; CCMNet, 2025, roughly 1 MB, driven by the same DNG calibration matrices this pipeline already reads). Adopt one only after artifact-level license review and a measured Delta E00 improvement on independent verification patches in the benchmark.

### 9.3 Scale and resolution terminology

- **Pixel dimensions:** dimensions of the output array.
- **Sampling frequency (ppi):** pixels divided by known or explicitly confirmed physical print dimensions.
- **Delivered resolution:** SFR/MTF measured with a physical target.
- **Reproduction-scale error:** difference between recorded and measured physical scale.

Never call pixel dimensions or an ARCore estimate measured DPI. Use PPI for image sampling; DPI describes printer dots. Authoritative PPI requires a coplanar ruler/reference target or confirmed physical dimensions. Standard-size snapping and ARCore/depth estimates are suggestions only.

### 9.4 Export and provenance

Android `Bitmap.compress()` does not provide TIFF or JPEG XL and does not establish preservation of high bit depth. Keep the high-bit-depth pipeline in native memory and integrate explicit audited encoders such as libtiff and libpng; add libjxl only after compatibility and packaging tests. Treat ordinary Android HEIF as an 8-bit derivative unless a separate high-bit-depth path is proven.

C2PA is feasible but deferred. Production use requires more than writing a manifest: signing identities, certificate enrollment and rotation, Android Keystore/StrongBox integration, timestamping, revocation, format validation, and a policy for stripped credentials. C2PA binds signed assertions to an asset; it does not prove that the assertions are true.

### 9.5 Identity-drift QA for generative derivatives

Generative face restoration can silently change who a person appears to be; one low-resolution input maps to many plausible restored identities. Before any face-touching generative derivative ships:

- Regression-test identity preservation with a face-embedding distance (ArcFace-class) and a landmark-fidelity metric between input and output, using pre-registered thresholds on a held-out set that includes small, blurred, and damaged faces.
- Recent restoration work (HonestFace, RestorerID) treats identity preservation as an explicit objective and supplies evaluation protocols; reuse the protocols even where those models are not used, subject to the Section 10 artifact review.
- A result that fails the identity checks must not be shown as a default output, and identity metrics belong in the release gates for every model update, since a quantization or distillation step can shift them.

## 10. Licensing controls

Replace component-level green checks with an artifact bill of materials containing URL, revision/hash, code license, weight license, base-model license, dataset/provenance, dependencies, notices, export implications, and patent review status.

### 10.1 Current disposition

| Disposition | Components and caveats |
|---|---|
| Candidate, permissive license identified | IPOL Handheld MFSR implementation (MIT); XFeat and bundled XFeat/LighterGlue weights (Apache-2.0); LightGlue matcher (Apache-2.0, excluding restrictive original SuperPoint assets); Glue Factory open SuperPoint; OpenCV 4.5+ core (Apache-2.0); UVDoc and DocRes (MIT); WindowSeat code/LoRA and its identified Qwen base (Apache-2.0). Still review dependencies, data provenance, notices, and patents. |
| Not approved as packaged | GFPGAN includes or identifies restricted StyleGAN2/DFDNet portions despite the repository's Apache label; IPOL HDR+ code is AGPL-3.0-or-later; EdgeSAM uses a non-commercial S-Lab license; original SuperPoint code/weights are restrictive; Real-ESRGAN's optional face enhancement pulls in GFPGAN. |
| Artifact review required | RDNet, DAI, OSEDiff's complete pipeline and base assets, DiffBIR, RoMa v2 dependencies/checkpoints, dewarping checkpoints, illuminant models, restoration checkpoints, and all public datasets. No training, distillation, distribution, or hosted inference until approved. |

An architecture can be independently reimplemented without copying code, but that does not resolve patents, weight licenses, base-model terms, dataset rights, or training-image provenance. Distillation from a restricted teacher is a counsel question, not an automatic workaround.

## 11. Roadmap

### Phase 0: evidence and legal gates (6 to 10 weeks)

- Build and document benchmark v0.
- Implement direct Camera2 RAW/YUV burst capture on at least one flagship and one mid-range device; separately measure CameraX sequential RAW as a fallback.
- Implement homography registration plus minimum, soft-minimum, median, and conservative confidence-weighted fusion.
- Run controlled comparisons against PhotoScan and the best single frame.
- Validate color/scale/SFR measurements with independent physical targets and OpenDICE where applicable.
- Measure capture cadence, metadata synchronization, buffer behavior, ARCore coexistence, memory, and thermal soak.
- Commission claim charts for the final candidate capture flow and the full relevant Google/Photomyne families.
- Create the artifact-level license BOM.
- Run 8 to 12 observed usability sessions on the unguided multi-view flow.

Deliverable: a reproducible technical report and Gate A through F decision, not a polished app.

### Phase 1: conservative capture MVP (3 to 4 months, conditional)

- Android capture state machine with CameraX preview and direct Camera2 quality path.
- Capability-driven RAW burst, YUV burst, and single-shot modes.
- Selected feature matcher + MAGSAC++ registration.
- Native-resolution conservative fusion with confidence/failure maps.
- One-photo quad detection, full-resolution refinement, rectification, and manual correction.
- Acquisition package, JPEG derivative, and high-bit-depth TIFF export.
- Batch queue for multiple independent scans, without album-page splitting.
- Instrumentation for failures, retries, latency, memory, and thermal behavior.

### Phase 2: measured differentiators (each separately gated)

- Print-specific residual de-glare model.
- MFSR if SFR gains survive fidelity and artifact tests.
- Print-specific curl correction.
- Album-page instance detection and segmentation after patent review.
- Back-of-photo camera HTR with mandatory confirmation.
- Physical-target archival workflow and published metric results.
- Learned illuminant estimation for target-free color (C5/CCMNet-class), gated on the Section 9.2 license review and measured verification-patch improvement.
- Accessibility work based on dedicated screen-reader, low-vision, motor, and eyes-free testing.

### Phase 3: explicitly derived outputs

- Descreening, dust/scratch removal, and fade correction as restored derivatives.
- Prior-driven super-resolution, face restoration, inpainting, and colorization as generative derivatives, each gated on the Section 9.5 identity-drift checks where faces are involved.
- C2PA only after the trust/signing and stripped-manifest product design is complete.
- Optional attested cloud processing only after privacy, economics, and deletion behavior are independently reviewed.

## 12. Open decisions and risks

Ranked by ability to invalidate or reshape the plan:

1. **Print-domain proof:** no cited paper proves that the proposed modern stack beats PhotoScan on glossy prints. Gate A is existential.
2. **Patent freedom to operate:** final implementation needs jurisdiction-specific claim charts. Patent records and proposed design-arounds must remain live review items.
3. **Android camera fragmentation:** RAW support, burst cadence, stream combinations, metadata, camera sharing, and thermals vary independently by device.
4. **Capture usability:** an unguided sweep may not produce enough useful baseline while staying easy for ordinary users.
5. **Fidelity:** aggressive glare suppression may remove legitimate image highlights, alter tone/color, or smear grain and halftone patterns.
6. **Ground truth:** flatbed captures and target captures must themselves be characterized; no single metric covers glare removal and visual fidelity.
7. **Licensing and provenance:** modern model repositories often mix licenses across code, weights, base models, and datasets.
8. **Physical scale:** without a target or confirmed dimensions, PPI remains an estimate and must not be marketed as measured.
9. **Performance:** model parameter count and vendor microbenchmarks do not predict full-resolution tiled latency, memory, power, or quality.
10. **Scope:** restoration, cloud processing, C2PA, album splitting, and archival claims can each consume a product cycle; none should block proof of the core scan.

## 13. Primary references

### Original product and status

- Google Research, PhotoScan technical explanation (2017): https://research.google/blog/photoscan-taking-glare-free-pictures-of-pictures/
- Google Photos help and current PhotoScan route: https://support.google.com/photos/answer/7177983
- Google Play listing: https://play.google.com/store/apps/details?id=com.google.android.apps.photos.scanner
- Apple App Store listing: https://apps.apple.com/us/app/photoscan-by-google-photos/id1165525994
- Microsoft Lens retirement schedule: https://support.microsoft.com/en-us/topic/retirement-of-microsoft-lens-fc965de7-499d-4d38-aeae-f6e48271652d

### Reflection, glare, fusion, and registration

- Obstruction-Free Photography (SIGGRAPH 2015): https://sites.google.com/site/obstructionfreephotography/
- Adobe, Removing Reflections from RAW Photos: https://arxiv.org/abs/2404.14414
- RDNet: https://arxiv.org/abs/2410.08063
- WindowSeat repository/model: https://github.com/huawei-bayerlab/windowseat-reflection-removal
- DocHR14K: https://arxiv.org/abs/2504.14238
- Handheld MFSR and IPOL implementation: https://sites.google.com/view/handheld-super-res/ and https://www.ipol.im/pub/art/2023/460/
- HDR+ IPOL implementation and AGPL record: https://www.ipol.im/pub/art/2021/336/
- XFeat: https://github.com/verlab/accelerated_features
- LightGlue: https://github.com/cvg/LightGlue
- Glue Factory: https://github.com/cvg/glue-factory
- OpenCV USAC/MAGSAC++: https://docs.opencv.org/4.x/de/d3e/tutorial_usac.html

### Android and provenance

- CameraX 1.5 RAW announcement: https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html
- CameraX `ImageCapture` API/source: https://android.googlesource.com/platform/frameworks/support/+/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageCapture.java
- Camera2 `CameraCaptureSession`: https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession
- Camera2 characteristics: https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- ARCore SDK/releases: https://github.com/google-ar/arcore-android-sdk/releases
- LiteRT: https://ai.google.dev/edge/litert
- Google Play target API policy: https://support.google.com/googleplay/android-developer/answer/11926878
- C2PA specification: https://spec.c2pa.org/specifications/specifications/2.4/specs/C2PA_Specification.html

### Archival imaging and color

- FADGI Technical Guidelines, Third Edition: https://www.digitizationguidelines.gov/guidelines/digitize-technical.html
- OpenDICE: https://www.digitizationguidelines.gov/guidelines/digitize-OpenDice.html
- ISO 19264-1:2021 overview: https://www.iso.org/standard/79172.html
- DNG specification: https://helpx.adobe.com/camera-raw/digital-negative.html
- FADGI TIFF metadata guidance: https://www.digitizationguidelines.gov/guidelines/TIFF_Metadata_Final.pdf
- C5 cross-camera color constancy (ICCV 2021): https://arxiv.org/abs/2011.11890
- CCMNet (2025): https://arxiv.org/abs/2504.07959

### Restoration evaluation

- HonestFace (identity-preserving face restoration and metrics, 2025): https://arxiv.org/abs/2505.18469
- RestorerID (reference-conditioned, identity-preserving restoration, 2024): https://arxiv.org/abs/2411.14125

### Patent records for counsel review

- Google obstruction-guided capture: https://patents.google.com/patent/US10412316B2/en and https://patents.google.com/patent/US11050948B2/en
- Google corner-guided composite: https://patents.google.com/patent/US10257485B2/en and https://patents.google.com/patent/US10531061B2/en
- Google flash/glare family example: https://patents.google.com/patent/US11483463B2/en
- Photomyne parent and continuation examples: https://patents.google.com/patent/US9754163B2/en and https://patents.google.com/patent/US10452905B2/en
- Microsoft flash/no-flash record: https://patents.google.com/patent/US7457477B2/en

Secondary reviews and app-store comments remain useful for forming interview questions and failure taxonomies, but primary documentation and reproducible tests control technical decisions in this plan.

## Appendix A: product rationale and positioning hypotheses (unvalidated)

This appendix preserves the original proposal's product rationale as input for the Section 2.3 hypothesis tests. Everything here derives from secondary sources — store listings, app reviews, press coverage, and market reports — and none of it is validated. It exists to structure interviews, review mining, and prioritization; it does not gate engineering decisions.

### A.1 Positioning hypotheses

- **Fidelity with receipts.** Incumbents under-deliver on resolution and on the honesty of their claims (PhotoScan's output cap and compression; a documented Photomyne export carried a default 72-dpi resolution tag). Publishing measured per-scan metrics using the Section 9.3 terminology could be a durable differentiator precisely because reviewers can verify it.
- **Three fragmented jobs in one app.** Review roundups suggest users combine apps today: multi-frame glare reduction (PhotoScan's distinctive strength), album-page batch capture (Photomyne's strength), and restoration (Reimagine-class apps). No product does all three well.
- **Privacy as a headline.** On-device processing is a marketable trust claim against cloud-processing competitors and mail-in services that require shipping irreplaceable originals. Photomyne faced an Illinois BIPA class action (filed July 2024, voluntarily dismissed September 2024) — a signal that face-related features in this category carry legal and trust sensitivity.
- **Anti-subscription pricing.** Subscription fatigue is a recurring complaint in incumbent reviews (Photomyne listed at $59.99/yr and Reimagine at $49.99/yr at research time) for what is often a one-time family project. The hypothesis: free capture plus a one-time purchase (roughly $20 to $40) for pro features converts better and generates favorable reviews.
- **Own what hardware cannot do.** Photos glued in albums, framed behind glass, oversized prints, and photos encountered at relatives' homes are structurally unserved by sheet-fed scanners and mail-in services.
- **Trust engineering as a feature.** Reviewers punish over-processing. Preserved originals, per-effect before/after toggles, and the Section 4.1 derivative separation are user-visible product claims, not only engineering hygiene.

### A.2 Demand signals (secondary, unverified)

- Market reports size photo-digitization services at roughly $2.6B (2024) growing toward a projected $5.3B by 2032. Direction-of-travel signal only; not TAM proof.
- Microsoft Lens retirement (removed from stores 2026-02-09; scanning disabled 2026-03-09) creates a displaced-user acquisition channel with partial audience overlap.
- User threads report PhotoScan install failures on some recent devices. Per Section 2.2 this is not general unavailability, but the associated search queries are a live, low-competition acquisition channel worth monitoring.
- Rating asymmetry between PhotoScan's iOS and Play listings (higher on iOS at research time) suggests platform-skewed satisfaction worth probing in interviews.

### A.3 Use of this appendix

Convert each bullet into an interview question or a scoped review-mining query per Section 2.3 before it influences pricing, positioning, or scope. Promote a hypothesis into the plan body only with the same evidence standard the rest of this document applies.
