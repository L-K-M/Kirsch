# Kirsch

[![Kirsch checks](https://github.com/L-K-M/Kirsch/actions/workflows/phase0.yml/badge.svg)](https://github.com/L-K-M/Kirsch/actions/workflows/phase0.yml)

Kirsch produces glare-reduced digital copies of printed photographs on Android. The repository contains the Phase 0 evidence tooling and the evidence-supported Phase 1–3 product paths from [`PLAN.md`](PLAN.md):

- a versioned physical-print benchmark protocol and standard-library validator
- capability-driven Camera2 RAW acquisition, YUV quality sweep, and single-frame capture
- on-device ORB/MAGSAC++ registration, exposure normalization, conservative temporal fusion, and confidence/failure maps
- automatic print geometry with manual corner correction, batch processing, JPEG and TIFF-container export, and derivative provenance
- explicit restored derivatives for descreening, dust/scratch removal, fade correction, and classical upscaling
- hard capability gates for features whose approved weights, identity evidence, credentials, or backend do not exist

## Quick Start

Validate the benchmark tooling:

```bash
python3 -m unittest discover -s benchmark/tests
python3 benchmark/tools/kirsch_benchmark.py validate benchmark/tests/fixtures/development/manifest.json
python3 benchmark/tools/kirsch_benchmark.py validate-scan /path/to/scan.json
```

Build the Android app after installing JDK 17 and Android SDK 35:

```bash
./gradlew testDebugUnitTest assembleDebug
```

See [`app/README.md`](app/README.md) for capture, processing, review, and export behavior. [`PHASES1-3.md`](PHASES1-3.md) maps the implementation to the roadmap and records evidence-driven boundaries.

[`PHASE0.md`](PHASE0.md) remains the immutable evidence record. A successful build is not represented as new physical, legal, usability, or image-quality evidence.
