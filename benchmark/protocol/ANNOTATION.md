# Annotation Protocol v0.1

## Coordinate Contract

All masks and geometry refer to the encoded source asset before EXIF orientation:

- origin: top-left
- x axis: right
- y axis: down
- unit: pixel
- sample location: pixel centers
- dimensions: exact declared source width and height

## Required Layers

Store masks as lossless 8-bit PNGs with value 0 for absent, 255 for present, and an optional documented ambiguity value.

| Layer | Meaning |
|---|---|
| `surface-glare-hotspot` | Compact surface specular/highlight |
| `surface-glare-sheen` | Broad view-dependent surface sheen |
| `glare-ambiguous` | Reflection cannot be distinguished reliably |
| `saturation-any-channel` | At least one source channel clipped under the frozen threshold |
| `saturation-all-channels` | All source channels clipped |
| `legitimate-highlight` | Highlight belongs to content printed in the photograph |

These layers may overlap. Saturation is evidence of clipping, not proof of surface glare. Surface glare may cover a legitimate highlight.

## Geometry

Record the visible print contour and ordered TL/TR/BR/BL corners where meaningful. Include occlusion and out-of-frame flags. Do not invent a hidden corner; use an explicit not-applicable/missing reason.

## Process

1. Generate method-blind randomized display proxies while retaining the original asset separately.
2. Record protocol hash, annotation tool/version, pseudonymous annotator, blinding state, and source asset hash.
3. Require independent review or adjudication for confirmatory annotations.
4. Accept an annotation set only after dimensions, coordinate system, required layers, and provenance validate.

When scoring a rectified output, map annotations to a fixed canonical physical-print region. Missing or cropped expected regions count under the preregistered failure rule; never shrink the denominator to reward cropping.
