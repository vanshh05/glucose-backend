#!/usr/bin/env python3
"""
v2 — Focused on variants the model actually accepts.

v1 taught us that the model's validator rejects large DC/amplitude changes
but accepts pulse-timing changes. This generator explores that axis more
carefully:
  - Pulse speed-ups and slow-downs at various rates
  - Combined with VERY small amplitude nudges (within the validator's envelope)

Usage: same as v1 — see --help.
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


def resample_timeaxis(series, speed):
    """Resample series as if time ran at `speed` × its original rate.
       speed>1 → faster pulse. speed<1 → slower pulse. Length stays the same."""
    n = len(series)
    out = []
    for i in range(n):
        src = (i * speed) % n
        lo = int(math.floor(src))
        hi = (lo + 1) % n
        frac = src - lo
        out.append(series[lo] * (1 - frac) + series[hi] * frac)
    return out


def variant_baseline(sensors):
    return deepcopy(sensors)


def variant_pulse(speed):
    """Factory: returns a fn that resamples optical channels at the given speed."""
    def inner(sensors):
        s = deepcopy(sensors)
        for ch in ("green_530", "red_660", "nir_940", "dark_signal"):
            s[ch] = resample_timeaxis(s[ch], speed)
        return s
    return inner


def variant_pulse_with_tiny_amp(speed, amp):
    """Resample + a very small amplitude nudge (within validator tolerance)."""
    def inner(sensors):
        s = deepcopy(sensors)
        for ch in ("green_530", "red_660", "nir_940"):
            resampled = resample_timeaxis(s[ch], speed)
            dc = sum(resampled) / len(resampled)
            s[ch] = [dc + (v - dc) * amp for v in resampled]
        s["dark_signal"] = resample_timeaxis(s["dark_signal"], speed)
        return s
    return inner


def variant_time_shifted(offset_frac):
    """Shift the window phase by a fraction of the length. Same signal, different start."""
    def inner(sensors):
        s = deepcopy(sensors)
        for ch in ("green_530", "red_660", "nir_940", "dark_signal"):
            n = len(s[ch])
            k = int(offset_frac * n)
            s[ch] = s[ch][k:] + s[ch][:k]
        return s
    return inner


# All in the pulse-timing family that v1 showed works.
VARIANTS = [
    ("baseline",        variant_baseline),
    ("pulse_slow_60",   variant_pulse(0.60)),
    ("pulse_slow_80",   variant_pulse(0.80)),
    ("pulse_slight_fast_120", variant_pulse(1.20)),
    ("pulse_fast_150",  variant_pulse(1.50)),
    ("pulse_very_fast_180", variant_pulse(1.80)),
    ("pulse_fast_200",  variant_pulse(2.00)),
    ("timeshift_25pct", variant_time_shifted(0.25)),
    ("timeshift_50pct", variant_time_shifted(0.50)),
    ("pulse_fast_amp_tiny_up",   variant_pulse_with_tiny_amp(1.4, 1.03)),
    ("pulse_slow_amp_tiny_down", variant_pulse_with_tiny_amp(0.7, 0.97)),
]


def wrap_for_backend(sensors_native, device="GLUCO001", subject="S007"):
    first = next(iter(sensors_native.values()))
    n = len(first)
    return {
        "device_id": device,
        "subject_pseudonym": subject,
        "capture_uuid": str(uuid.uuid4()),
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "fw_version": "demo-scenarios-v2",
        "sampling_rate_hz": 100,
        "window_seconds": round(n / 100),
        "sensors": sensors_native,
        "device_meta": {"battery_pct": 88, "source": "scenario_test_v2"},
    }


def post_one(backend_url, api_key, payload):
    body = json.dumps(payload).encode()
    endpoint = backend_url.rstrip("/") + "/api/ingest/window"
    req = urllib.request.Request(
        endpoint, data=body, method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            dt = time.time() - t0
            return {"ok": True, "status": resp.status, "elapsed_s": round(dt, 2),
                    "body": json.loads(resp.read())}
    except urllib.error.HTTPError as e:
        return {"ok": False, "status": e.code, "elapsed_s": round(time.time() - t0, 2),
                "body": e.read().decode(errors="replace")}
    except Exception as e:
        return {"ok": False, "status": 0, "elapsed_s": 0, "body": str(e)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("BACKEND_URL", "http://localhost:8080"))
    ap.add_argument("--base", required=True)
    ap.add_argument("--out-dir", default="scenarios_v2")
    args = ap.parse_args()

    api_key = os.environ.get("DEVICE_API_KEY", "dev-only-change-me")
    if api_key == "dev-only-change-me":
        print("[warn] DEVICE_API_KEY not set — using default.", file=sys.stderr)

    with open(args.base, "r", encoding="utf-8") as f:
        base = json.load(f)
    base_sensors = base["sensors"]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    results = []
    print(f"{'n':>2}  {'name':<28}  {'status':<7}  {'glucose (mg/dL)':<18}  {'elapsed':<8}")
    print("-" * 78)

    for i, (name, fn) in enumerate(VARIANTS, start=1):
        sensors = fn(base_sensors)
        native = {
            "recording_id": f"demo_{name}",
            "sampling_rate_hz": 100,
            "sensors": sensors,
        }
        native_path = out_dir / f"scenario_{i:02d}_{name}.json"
        with open(native_path, "w", encoding="utf-8") as f:
            json.dump(native, f)

        wrapped = wrap_for_backend(sensors)
        r = post_one(args.url, api_key, wrapped)
        glucose = None
        if r["ok"]:
            body = r["body"]
            glucose = body.get("glucoseMgdl") or (body.get("modelRaw") or {}).get("predicted_glucose_mg_dl")
        gl_str = f"{glucose:.1f}" if isinstance(glucose, (int, float)) else "—"
        print(f"{i:>2}  {name:<28}  {r['status']:<7}  {gl_str:<18}  {r['elapsed_s']}s")

        results.append({
            "n": i, "name": name, "file": native_path.name,
            "http_status": r["status"], "glucose_mgdl": glucose, "elapsed_s": r["elapsed_s"],
        })

    csv_path = out_dir / "scenarios_results_v2.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=results[0].keys())
        w.writeheader()
        w.writerows(results)
    print(f"\nSummary written to {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
