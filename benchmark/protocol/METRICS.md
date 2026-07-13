# Metric Specification v0.1

## Analysis Unit

The physical print is the scoring unit. Repeated captures, blocks, devices, and frames are nested observations. If a source group contains related prints, uncertainty resamples or clusters at `source_group_id`.

## Candidate Primary Endpoint

For protocol development, evaluate residual surface glare in a fixed canonical print region:

```text
residual surface-glare pixels / predefined evaluable physical-print pixels
```

The confirmatory preregistration must freeze the exact layer combination, canonical mapping, ambiguity rule, aggregation, practical margin, and confidence-bound decision. Do not finalize the endpoint from confirmatory results.

## Comparisons

- Kirsch conservative fusion versus current PhotoScan.
- Kirsch conservative fusion versus the frozen non-oracle single-frame selector.
- Within-print paired differences after the preregistered repeat/block aggregation.
- Candidate superiority requires clearing the practical margin against both comparators.

## No-Regression Endpoints

- ghosting/double-image rate
- legitimate-highlight loss
- independent verification-target Delta E00
- geometry/aspect-ratio error
- completion and capture failure rate
- delivered SFR/MTF where a physical target is present
- runtime, memory, energy, and thermal limits on named devices

PSNR and no-reference aesthetic scores may be secondary diagnostics only. They can reward smoothing, darkening, or invented detail.

## Missingness

The preregistration must define scores for failed acquisition, incomplete output, missing/cropped expected regions, invalid registration, and excluded prints. Failed attempts remain in the manifest. Exclusion decisions must be made without access to comparative method scores.

## Uncertainty

Specify source-group clustered bootstrap or a complete hierarchical model, including resampling unit, number of resamples, seed, interval type, unequal group handling, multiplicity, and all transformations. Power/sample-size calculations use source groups or print-level contrasts, never frames.
