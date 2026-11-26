#!/usr/bin/env python3
"""
Generate a palette of demo payload variants from a base holdout JSON, send each
through the backend, record the glucose value the model returned, and save the
interesting ones as named scenarios for the frontend.

The modifications are physiologically-motivated — different perfusion levels,
pulse amplitudes, ratiometric balance, temperature, motion. The GCN is
non-linear so we can't predict what each variant will produce; we POST them,
see what the model says, and curate.

Usage:
    python scripts/generate_demo_scenarios.py \
        --url https://glucose-backend-llnb.onrender.com \
        --base path\to\demo_prediction_request.json \
        --out-dir .\scenarios

Needs env var DEVICE_API_KEY.

Output:
    scenarios/
       scenario_01_baseline.json
       scenario_02_...
       scenarios_results.csv   (what the model returned for each)
"""

import argparse
import csv
import json
import math
import os
import sys
import time
import urllib.request
import urllib.error
import uuid
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


# ---------------------------------------------------------------------------
# Variant generators. Each returns a modified copy of the base sensors dict.
# Modifications are intentionally subtle — the model is sensitive and we don't
# want to push inputs outside its supported range.
# ---------------------------------------------------------------------------

def variant_baseline(sensors):
    return deepcopy(sensors)


def variant_strong_perfusion(sensors):
    """Higher pulsatile amplitude — healthy blood flow, warm finger."""
    s = deepcopy(sensors)
    for ch in ("green_530", "red_660", "nir_940"):
        dc = sum(s[ch]) / len(s[ch])
        s[ch] = [dc + (v - dc) * 1.35 for v in s[ch]]
    return s


def variant_weak_perfusion(sensors):
    """Smaller pulsatile amplitude — cold finger, light contact."""
    s = deepcopy(sensors)
    for ch in ("green_530", "red_660", "nir_940"):
        dc = sum(s[ch]) / len(s[ch])
        s[ch] = [dc + (v - dc) * 0.55 for v in s[ch]]
    s["skin_temp"] = [t - 1.5 for t in s["skin_temp"]]
    return s


def variant_high_dc_optical(sensors):
    """DC level up ~15% — simulates brighter LED drive or lighter skin tone."""
    s = deepcopy(sensors)
    for ch in ("green_530", "red_660", "nir_940"):
        s[ch] = [v * 1.15 for v in s[ch]]
    return s


def variant_low_dc_optical(sensors):
    """DC level down ~20% — simulates darker skin or weaker coupling."""
    s = deepcopy(sensors)
    for ch in ("green_530", "red_660", "nir_940"):
        s[ch] = [v * 0.80 for v in s[ch]]
    return s


def variant_red_shifted(sensors):
    """Red channel relatively higher, IR relatively lower.
    Classic oximetry-style shift that can signal different vascular state."""
    s = deepcopy(sensors)
    s["red_660"] = [v * 1.10 for v in s["red_660"]]
    s["nir_940"] = [v * 0.92 for v in s["nir_940"]]
    return s


def variant_ir_dominant(sensors):
    """IR channel elevated, red down. Opposite ratiometric shift."""
    s = deepcopy(sensors)
    s["red_660"] = [v * 0.90 for v in s["red_660"]]
    s["nir_940"] = [v * 1.12 for v in s["nir_940"]]
    return s


def variant_warm_finger(sensors):
    """Skin temperature +2°C — warm environment / after exercise."""
    s = deepcopy(sensors)
    s["skin_temp"] = [t + 2.0 for t in s["skin_temp"]]
    # Warm finger → slightly more perfusion
    for ch in ("green_530", "red_660", "nir_940"):
        dc = sum(s[ch]) / len(s[ch])
        s[ch] = [dc + (v - dc) * 1.12 for v in s[ch]]
    return s


def variant_cool_finger(sensors):
    """Skin temperature -3°C — cold room, poor circulation."""
    s = deepcopy(sensors)
    s["skin_temp"] = [t - 3.0 for t in s["skin_temp"]]
    for ch in ("green_530", "red_660", "nir_940"):
        dc = sum(s[ch]) / len(s[ch])
        s[ch] = [dc + (v - dc) * 0.80 for v in s[ch]]
    return s


def variant_faster_pulse(sensors):
    """Slightly higher heart-rate signature by compressing the pulsatile shape.
    Approximate: resample the AC component to simulate ~1.5× pulse rate."""
    s = deepcopy(sensors)
    for ch in ("green_530", "red_660", "nir_940"):
        n = len(s[ch])
        dc = sum(s[ch]) / n
        # Interpolate with a scale factor on the time index — approximate speed-up
        new_series = []
        for i in range(n):
            # Pull sample from position i * 1.5 (wrap) in the original
            src = (i * 1.5) % n
            lo = int(math.floor(src))
            hi = (lo + 1) % n
            frac = src - lo
            v = s[ch][lo] * (1 - frac) + s[ch][hi] * frac
            new_series.append(v)
        s[ch] = new_series
    return s


VARIANTS = [
    ("baseline",         variant_baseline),
    ("strong_perfusion", variant_strong_perfusion),
    ("weak_perfusion",   variant_weak_perfusion),
    ("high_dc",          variant_high_dc_optical),
    ("low_dc",           variant_low_dc_optical),
    ("red_shifted",      variant_red_shifted),
    ("ir_dominant",      variant_ir_dominant),
    ("warm_finger",      variant_warm_finger),
    ("cool_finger",      variant_cool_finger),
    ("faster_pulse",     variant_faster_pulse),
]


# ---------------------------------------------------------------------------

def wrap_for_backend(sensors_native, device="GLUCO001", subject="S007"):
    """Wrap the model-native sensors dict into the device envelope."""
    first = next(iter(sensors_native.values()))
    n = len(first)
    # base_demo already uses 100 Hz; keep that
    return {
        "device_id": device,
        "subject_pseudonym": subject,
        "capture_uuid": str(uuid.uuid4()),
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "fw_version": "demo-scenarios",
        "sampling_rate_hz": 100,
        "window_seconds": round(n / 100),
        "sensors": sensors_native,
        "device_meta": {"battery_pct": 88, "source": "scenario_test"},
    }


def post_one(backend_url, api_key, payload):
    body = json.dumps(payload).encode()
    endpoint = backend_url.rstrip("/") + "/api/ingest/window"
    req = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            dt = time.time() - t0
            return {
                "ok": True,
                "status": resp.status,
                "elapsed_s": round(dt, 2),
                "body": json.loads(resp.read()),
            }
    except urllib.error.HTTPError as e:
        dt = time.time() - t0
        return {
            "ok": False,
            "status": e.code,
            "elapsed_s": round(dt, 2),
            "body": e.read().decode(errors="replace"),
        }
    except Exception as e:
        return {"ok": False, "status": 0, "elapsed_s": 0, "body": str(e)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("BACKEND_URL", "http://localhost:8080"))
    ap.add_argument("--base", required=True, help="Path to the baseline demo JSON")
    ap.add_argument("--out-dir", default="scenarios")
    ap.add_argument("--only-generate", action="store_true",
                    help="Just write variant JSONs, don't POST them")
    args = ap.parse_args()

    api_key = os.environ.get("DEVICE_API_KEY", "dev-only-change-me")
    if not args.only_generate and api_key == "dev-only-change-me":
        print("[warn] Using default DEVICE_API_KEY — set the env var to match your backend.",
              file=sys.stderr)

    with open(args.base, "r", encoding="utf-8") as f:
        base = json.load(f)
    base_sensors = base["sensors"]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    results = []
    print(f"{'n':>2}  {'name':<20}  {'status':<7}  {'glucose (mg/dL)':<18}  {'elapsed':<8}")
    print("-" * 70)

    for i, (name, fn) in enumerate(VARIANTS, start=1):
        sensors = fn(base_sensors)
        # Write the native-format JSON (what the frontend will consume)
        native = {
            "recording_id": f"demo_{name}",
            "sampling_rate_hz": 100,
            "sensors": sensors,
        }
        native_path = out_dir / f"scenario_{i:02d}_{name}.json"
        with open(native_path, "w", encoding="utf-8") as f:
            json.dump(native, f)

        if args.only_generate:
            print(f"{i:>2}  {name:<20}  (saved)   —                   —")
            continue

        # POST through the backend
        wrapped = wrap_for_backend(sensors)
        result = post_one(args.url, api_key, wrapped)

        glucose = None
        if result["ok"]:
            body = result["body"]
            glucose = body.get("glucoseMgdl")
            raw = body.get("modelRaw", {})
            if glucose is None:
                glucose = raw.get("predicted_glucose_mg_dl")

        status_str = f"{result['status']}"
        gl_str = f"{glucose:.1f}" if isinstance(glucose, (int, float)) else "—"
        print(f"{i:>2}  {name:<20}  {status_str:<7}  {gl_str:<18}  {result['elapsed_s']}s")

        results.append({
            "n": i,
            "name": name,
            "file": native_path.name,
            "http_status": result["status"],
            "glucose_mgdl": glucose,
            "elapsed_s": result["elapsed_s"],
            "model_note": (result["body"].get("modelRaw", {}) if result["ok"] else {}).get("note", ""),
        })

    # Save a summary CSV
    if not args.only_generate and results:
        csv_path = out_dir / "scenarios_results.csv"
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=results[0].keys())
            w.writeheader()
            w.writerows(results)
        print(f"\nSummary written to {csv_path}")
        print("\nNow pick 3–4 scenarios with meaningfully different glucose values,")
        print("rename their files to something meaningful (e.g. scenario_fasting.json,")
        print("scenario_post_meal.json), and copy them into your frontend's public/ dir.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
