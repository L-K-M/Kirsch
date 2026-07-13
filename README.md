# Kirsch

[![Phase 0 checks](https://github.com/L-K-M/Kirsch/actions/workflows/phase0.yml/badge.svg)](https://github.com/L-K-M/Kirsch/actions/workflows/phase0.yml)

Kirsch is a research prototype for producing glare-reduced digital copies of printed photographs. The repository currently implements the executable parts of Phase 0 from [`PLAN.md`](PLAN.md):

- a versioned physical-print benchmark protocol and standard-library validator
- a direct Android Camera2 RAW/YUV burst-capture prototype
- unit tests for manifest integrity and camera-independent capture logic

## Quick Start

Validate the benchmark tooling:

```bash
python3 -m unittest discover -s benchmark/tests
python3 benchmark/tools/kirsch_benchmark.py validate benchmark/tests/fixtures/development/manifest.json
```

Build the Android prototype after installing JDK 17 and Android SDK 35:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Physical-print collection and device qualification are intentionally not represented as completed work. See [`benchmark/README.md`](benchmark/README.md) and [`app/README.md`](app/README.md) for the required hardware procedures.

[`PHASE0.md`](PHASE0.md) records delivered tooling, the pending physical work, device-matrix fields, and gate status without conflating a successful build with experimental evidence.
