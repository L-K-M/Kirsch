#!/usr/bin/env python3
"""Offline analysis for Camera2 capture packages.

Unlike the validator, this tool needs third-party packages (numpy,
opencv-python-headless); it is a desk-analysis aid, not part of the
standard-library benchmark toolchain, and nothing here ships in the app.

Subcommands:
  audit <capture-dir>            Check per-frame metadata consistency.
  fuse  <capture-dir> [--out D]  Register the burst and write baseline
                                 composites (min / soft-min / median) plus a
                                 JSON report and downscaled previews.

The fuse baselines are the Phase 0 comparators from PLAN.md section 5.2, not
product output.
"""

import argparse
import json
import pathlib
import sys


def load_package(cap_dir):
    cap_dir = pathlib.Path(cap_dir)
    manifest = json.loads((cap_dir / "capture.json").read_text())
    frames = []
    for entry in manifest["frames"]:
        meta_path = next(
            cap_dir / f["path"] for f in entry["files"] if f["role"] == "capture-metadata"
        )
        payloads = [f for f in entry["files"] if f["role"] in ("i420", "raw-sensor", "dng")]
        frames.append(
            {
                "entry": entry,
                "meta": json.loads(meta_path.read_text()),
                "payload": (cap_dir / payloads[0]["path"]) if payloads else None,
                "payload_role": payloads[0]["role"] if payloads else None,
            }
        )
    return manifest, frames


def _exposure_product(meta):
    exposure = meta.get("sensor_exposure_time_ns")
    iso = meta.get("sensor_sensitivity_iso")
    return exposure * iso if exposure and iso else None


def audit(cap_dir):
    manifest, frames = load_package(cap_dir)
    metas = [f["meta"] for f in frames]
    findings = []
    notes = []

    products = [p for p in (_exposure_product(m) for m in metas) if p is not None]
    if len(products) < len(metas):
        findings.append(
            f"exposure/ISO metadata missing on {len(metas) - len(products)}/{len(metas)} frames"
        )
    if products:
        spread = (max(products) - min(products)) / max(products)
        exposures = {
            (m["sensor_exposure_time_ns"], m["sensor_sensitivity_iso"])
            for m in metas
            if _exposure_product(m) is not None
        }
        if len(exposures) > 1:
            findings.append(
                f"exposure/ISO changed mid-burst across {len(exposures)} settings "
                f"(exposure*gain spread {spread * 100:.3f}%) — normalize by the product"
            )
        if spread > 0.01:
            findings.append(
                f"exposure*gain product varies by {spread * 100:.2f}% (>1%): "
                "radiometric normalization required before fusion"
            )

    gain_sets = {tuple(m.get("color_correction_gains", [])) for m in metas}
    if len(gain_sets) > 1:
        findings.append(f"color-correction gains changed mid-burst: {sorted(gain_sets)}")
    gains = metas[0].get("color_correction_gains")
    if gains and len(gains) == 4:
        r, g_even, g_odd, b = gains
        gains_in_range = all(0.5 <= v <= 8 for v in gains)
        split = abs(g_even - g_odd) / max(g_even, g_odd) if gains_in_range else 1.0
        if split > 0.05 or not gains_in_range:
            # Field evidence (GRL-AL10): the HAL reports junk gains in
            # results regardless of mode. They only harm the image when AWB
            # is OFF and the values are actually applied; under auto/locked
            # AWB they are informational noise.
            if metas[0].get("awb_mode") == 0:
                findings.append(
                    f"implausible WB gains {gains} (green split {split * 100:.0f}%) "
                    "applied with AWB off: unrecoverable color cast; prefer AWB lock"
                )
            else:
                notes.append(
                    f"HAL reports implausible informational WB gains {gains} under "
                    "auto/locked AWB; do not trust result-reported gains on this device"
                )

    focus = {m.get("lens_focus_distance_diopters") for m in metas}
    if len(focus) > 1:
        findings.append(f"focus distance changed mid-burst: {sorted(focus)}")
    fired = [m for m in metas if m.get("flash_state") == 3]
    if fired and len(fired) != len(metas):
        findings.append(f"flash fired in {len(fired)}/{len(metas)} frames: mixed illumination stack")

    ts = [m.get("sensor_timestamp_ns") for m in metas]
    missing_ts = sum(t is None for t in ts)
    if missing_ts:
        findings.append(f"sensor_timestamp_ns missing on {missing_ts}/{len(metas)} frames")
    present = [t for t in ts if t is not None]
    deltas = [(b - a) / 1e6 for a, b in zip(present, present[1:])]
    report = {
        "capture_id": manifest["capture_id"],
        "mode": manifest["mode"],
        "frames": len(frames),
        "inter_frame_ms": [round(d, 1) for d in deltas],
        "exposure_gain_products": products,
        "findings": findings,
        "notes": notes,
    }
    print(json.dumps(report, indent=2))
    return 1 if findings else 0


def fuse(cap_dir, out_dir):
    import cv2
    import numpy as np

    manifest, frames = load_package(cap_dir)
    if any(f["payload_role"] != "i420" for f in frames):
        sys.exit("fuse currently supports i420 payloads only")
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    first = frames[0]["entry"]
    w, h = first["width"], first["height"]
    imgs, metas = [], []
    for f in frames:
        raw = np.fromfile(f["payload"], dtype=np.uint8)
        imgs.append(cv2.cvtColor(raw.reshape(h * 3 // 2, w), cv2.COLOR_YUV2BGR_I420))
        metas.append(f["meta"])

    ref = len(imgs) // 2
    ref_prod = _exposure_product(metas[ref])
    gains = []
    for m in metas:
        prod = _exposure_product(m)
        gains.append(ref_prod / prod if ref_prod and prod else 1.0)

    orb = cv2.ORB_create(nfeatures=8000)
    bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    ref_gray = cv2.cvtColor(imgs[ref], cv2.COLOR_BGR2GRAY)
    kp_ref, des_ref = orb.detectAndCompute(ref_gray, None)
    if des_ref is None or len(des_ref) < 4:
        sys.exit(f"reference frame {ref} has too few features to register against")

    aligned, masks, reg = [], [], []
    for i, img in enumerate(imgs):
        if i == ref:
            aligned.append(img.astype(np.float32))
            masks.append(np.ones((h, w), bool))
            reg.append({"frame": i, "reference": True})
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        kp, des = orb.detectAndCompute(gray, None)
        if des is None or len(des) < 4:
            reg.append({"frame": i, "error": "insufficient_features"})
            aligned.append(img.astype(np.float32))
            masks.append(np.zeros((h, w), bool))
            continue
        pairs = bf.knnMatch(des, des_ref, k=2)
        good = [p[0] for p in pairs if len(p) >= 2 and p[0].distance < 0.75 * p[1].distance]
        if len(good) < 4:
            reg.append({"frame": i, "error": "insufficient_matches", "matches": len(good)})
            aligned.append(img.astype(np.float32))
            masks.append(np.zeros((h, w), bool))
            continue
        src = np.float32([kp[m.queryIdx].pt for m in good]).reshape(-1, 1, 2)
        dst = np.float32([kp_ref[m.trainIdx].pt for m in good]).reshape(-1, 1, 2)
        hom, inliers = cv2.findHomography(src, dst, cv2.USAC_MAGSAC, 3.0)
        if hom is None or inliers is None:
            reg.append({"frame": i, "error": "homography_failed", "matches": len(good)})
            aligned.append(img.astype(np.float32))
            masks.append(np.zeros((h, w), bool))
            continue
        inliers = inliers.ravel().astype(bool)
        proj = cv2.perspectiveTransform(src[inliers], hom)
        resid = float(np.sqrt(((proj - dst[inliers]) ** 2).sum(axis=2)).mean())
        center = np.float32([[[w / 2, h / 2]]])
        shift = float(np.linalg.norm(cv2.perspectiveTransform(center, hom) - center))
        aligned.append(cv2.warpPerspective(img, hom, (w, h)).astype(np.float32))
        masks.append(cv2.warpPerspective(np.full((h, w), 255, np.uint8), hom, (w, h)) > 0)
        reg.append(
            {
                "frame": i,
                "matches": len(good),
                "inliers": int(inliers.sum()),
                "mean_residual_px": round(resid, 2),
                "center_shift_px": round(shift, 1),
            }
        )

    stack = np.stack(aligned)
    for i, g in enumerate(gains):
        stack[i] *= g
    valid = np.stack(masks)
    luma = stack.mean(axis=3)
    luma_masked = np.where(valid, luma, np.inf)

    sat = (luma >= 250) & valid
    n_valid = valid.sum(axis=0)
    all_sat = (sat.sum(axis=0) == n_valid) & (n_valid >= 3)

    idx_min = luma_masked.argmin(axis=0)
    comp_min = np.take_along_axis(stack, idx_min[None, :, :, None], axis=0)[0]
    tau = 12.0
    weights = np.exp(-(luma - luma_masked.min(axis=0)[None]) / tau) * valid
    comp_soft = (stack * weights[..., None]).sum(axis=0)
    comp_soft /= np.maximum(weights.sum(axis=0)[..., None], 1e-6)
    order = np.argsort(luma_masked, axis=0)
    mid = np.maximum((n_valid - 1) // 2, 0)
    med_idx = np.take_along_axis(order, mid[None], axis=0)[0]
    comp_med = np.take_along_axis(stack, med_idx[None, :, :, None], axis=0)[0]

    composites = {
        "single": stack[ref],
        "min": comp_min,
        "softmin": comp_soft,
        "median": comp_med,
    }
    residual_sat = {}
    for name, img in composites.items():
        arr = np.clip(img, 0, 255).astype(np.uint8)
        small = cv2.resize(arr, (w // 4, h // 4), interpolation=cv2.INTER_AREA)
        cv2.imwrite(str(out / f"{name}.png"), small)
        residual_sat[name] = round(float((img.mean(axis=2) >= 250).mean()) * 100, 4)
    cv2.imwrite(
        str(out / "valid_count.png"),
        (n_valid * (255 // max(len(imgs), 1))).astype(np.uint8),
    )

    report = {
        "capture_id": manifest["capture_id"],
        "reference_frame": ref,
        "exposure_norm_gains": [round(g, 5) for g in gains],
        "registration": reg,
        "saturated_fraction_pct": {
            "union": round(float(sat.any(axis=0).mean()) * 100, 3),
            "in_every_valid_frame": round(float(all_sat.mean()) * 100, 4),
        },
        "residual_saturated_pct": residual_sat,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_audit = sub.add_parser("audit")
    p_audit.add_argument("capture_dir")
    p_fuse = sub.add_parser("fuse")
    p_fuse.add_argument("capture_dir")
    p_fuse.add_argument("--out", default="fuse-out")
    args = parser.parse_args()
    if args.cmd == "audit":
        sys.exit(audit(args.capture_dir))
    sys.exit(fuse(args.capture_dir, args.out))


if __name__ == "__main__":
    main()
