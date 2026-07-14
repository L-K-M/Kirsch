# Product sweep audit: HUAWEI GRL-AL10, 2026-07-14

Two complete product-path YUV sweep captures were inspected:
`capture-20260714t081825750z-bfe71e2a` and
`capture-20260714t081831890z-c60d926c`. Both include all nine I420 frames,
per-frame metadata, and on-device scan outputs. The first scan was manually
rectified, restored, and accepted; the second remained in review.

## Capture integrity

- Both capture manifests pass `kirsch_benchmark.py validate-capture`.
- Both captures received and persisted 9/9 frames with no capture errors.
- Cadence was exactly 40.0ms between every frame (25fps), so each nine-frame
  sequence spans only 320ms.
- Exposure was constant at 9.874ms and ISO 50 in every frame. Focus distance,
  flash state, and image/result timestamps were also stable.
- AE and AWB locks were requested and reported locked. The HAL again reported
  implausible color-correction gains, but AWB remained enabled, so these are
  the previously documented informational values rather than gains applied
  with AWB off.
- Capture took 2.29s and 2.45s including persistence. Thermal status remained
  `LIGHT` (2) in both runs. Reported Java heap use at completion was 17.3MB and
  52.2MB; two samples are not a memory or thermal-soak result.

## Registration and glare baseline

The offline nine-frame ORB + MAGSAC++ baseline registered every frame. Each
non-reference frame had 2,295-3,973 inliers and a 1.01-1.16px mean residual.
Registration is not the limiting factor in these captures.

| Capture | Maximum center shift | Single saturated | All-frame floor | Minimum composite | Relative reduction |
|---|---:|---:|---:|---:|---:|
| `bfe71e2a` | 27.8px | 4.1483% | 3.6072% | 3.6072% | 13.0% |
| `c60d926c` | 42.7px | 4.4847% | 4.0861% | 4.0861% | 8.9% |

In both captures the per-pixel minimum exactly reaches the
saturated-in-every-valid-frame floor. This repeats the earlier estimator
result: fusion recovers all saturation that the captured views make
recoverable. The remaining 3.6-4.1% whole-frame saturated area is a large,
visually obvious glare core that remains saturated in every registered view.
The 28-43px sweep displacement is much smaller than the glare footprint.

The product processor loaded the intended evenly spaced subset (frames
0, 2, 4, 6, and 8), accepted all five registrations, and completed in 8.85s
and 8.26s without a thermal-status increase. Its conservative low-percentile
fusion left 4.0839% and 4.4643% mean-luma saturation respectively. This is
expected to retain more glare than the diagnostic minimum: the product
estimator deliberately avoids selecting an isolated dark sample. The captures
do not yet provide enough view diversity for that robustness tradeoff to be
useful.

## Product-path finding

The quality profile requested nine frames at 125ms intervals, nominally a
one-second sweep. On this camera the request is advisory because the device
lacks manual sensor control; `captureBurstRequests` delivered frames at its
native 40ms cadence instead. The package warning correctly records this, but
the resulting 320ms acquisition does not enforce the geometry the quality
path needs. The product can report successful fusion while retaining most of
the dominant glare.

This is evidence against treating a nine-request burst as a paced sweep on
the GRL-AL10 device class. The next implementation experiment should submit
individual captures on a monotonic schedule (while retaining timestamp-based
verification), or collect a longer native-rate burst and select frames by
baseline and sharpness. The target is view displacement greater than the
specular footprint, not merely a larger frame count. A wider or tilted user
motion is also required; these two captures do not establish Gate A.

## Packaging note

The scan manifests and derivative hashes are internally present, but
`validate-scan` cannot validate this exported layout directly because it
expects the paired capture under `acquisitions/<scan_id>/capture.json`, while
the data drop stores captures and scans in sibling top-level trees. This is a
data-drop layout mismatch, not evidence that either capture manifest is
invalid.
