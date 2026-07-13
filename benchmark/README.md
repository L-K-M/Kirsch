# Kirsch Print Benchmark v0

The benchmark evaluates glare removal as a physical capture problem. It does not treat frames from one burst as independent samples and does not treat a flatbed scan as unquestionable ground truth.

## Current Scope

The repository provides the protocol, manifests, validator, and a small structural fixture. Building the real benchmark requires physical prints, a characterized flatbed scanner, targets, at least two Android phones, and current PhotoScan captures.

Use two physically and operationally separate phases:

| Phase | Purpose | Permitted splits | Visibility |
|---|---|---|---|
| `protocol-development` | Refine capture, labels, metrics, margins, and sample size | `dev-train`, `dev-validation` | May be inspected repeatedly |
| `confirmatory` | Run the frozen comparison once | `confirmatory-test` | Quarantined until planned unblinding |

The first 50 physical prints described in `PLAN.md` are protocol-development data. They are not by themselves evidence of superiority.

## Layout

```text
benchmark/
  protocol/               Human procedures and preregistration template
  schema/                 Benchmark, annotation, and capture-package contracts
  tools/                  Standard-library Python validator
  tests/                  Validator tests and minimal checked fixture
  data/                   Ignored local payload root
```

A real data release should use:

```text
release/
  manifest.json
  manifest.sha256
  assets/
    protocols/
    source-groups/<source-group-id>/prints/<print-id>/...
    targets/
    software/
```

All paths in `manifest.json` are relative POSIX paths. The validator rejects absolute paths, traversal, backslashes, symlinks, missing files, size mismatches, and hash mismatches.

## Validate

```bash
python3 benchmark/tools/kirsch_benchmark.py version
python3 benchmark/tools/kirsch_benchmark.py validate \
  benchmark/tests/fixtures/development/manifest.json
python3 benchmark/tools/kirsch_benchmark.py validate-capture \
  /path/to/capture/capture.json
python3 -m unittest discover -s benchmark/tests
```

Use `--json` for stable diagnostic records in ingestion jobs.

## Collection Order

1. Assign `source_group_id` before splitting. Reprints, crops, copies, scans from the same negative/master, and likely near-duplicates belong to one group.
2. Record print rights, physical dimensions, process/finish, enclosure, geometry, and content strata.
3. Qualify the flatbed and verification target. Profile-creation and verification must use different target instances.
4. Define comparison blocks that hold print, lighting, device, enclosure, and target arrangement fixed.
5. Randomize modality order within each repeat. A repeat includes a physical reset; another burst frame is not another repeat.
6. Keep failed and rejected attempts. Do not overwrite them with a successful retry.
7. Hash every original payload and sidecar before annotation or processing.
8. Annotate in encoded-file coordinates under the frozen annotation protocol.
9. Freeze the comparator versions, primary endpoint, margin, failure rule, and analysis implementation before confirmatory collection.

## Privacy

Family photographs may contain faces, addresses, handwriting, location clues, and embedded metadata. Obtain a documented rights basis before capture, use pseudonymous operator/collection identifiers, strip unrelated GPS data from display derivatives, and keep raw/confirmatory data under an explicit access policy. SHA-256 proves byte integrity, not consent or provenance.
