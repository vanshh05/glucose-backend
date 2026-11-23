#!/usr/bin/env python3
"""
Fake ESP32 — posts a synthetic 30-second window to the Spring Boot backend.

Use this to test the whole pipeline (backend → model → response) without a
real ESP32 in hand.

Usage:
    python3 scripts/send_fake_window.py
    python3 scripts/send_fake_window.py --url http://localhost:8080
    python3 scripts/send_fake_window.py --subject S012

Needs an env var:
    DEVICE_API_KEY=<same value the backend is configured with>

Writes nothing to disk. Prints the backend response.
"""

import argparse
import json
import math
import os
import random
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timezone

CHANNELS = [
    "green_530", "red_660", "nir_940", "dark_signal",
    "skin_temp", "pressure", "accel_x", "accel_y", "accel_z",
]
N_SAMPLES = 3000
SAMPLE_RATE_HZ = 100


def synthesise_window() -> dict:
    """Produce plausible-looking arrays — PPG-ish waveform for optical channels,
    steady values for environmental channels, tiny jitter for accel."""
    t = [i / SAMPLE_RATE_HZ for i in range(N_SAMPLES)]
    # ~1.2 Hz heart rate → ~72 bpm
    heart = [0.03 * math.sin(2 * math.pi * 1.2 * x) for x in t]

    def optical(baseline: float) -> list[float]:
        return [baseline + h + random.gauss(0, 0.005) for h in heart]

    return {
        "green_530":  optical(1.0),
        "red_660":    optical(0.8),
        "nir_940":    optical(1.2),
        "dark_signal":[0.02 + random.gauss(0, 0.003) for _ in t],
        "skin_temp":  [33.1 + random.gauss(0, 0.05) for _ in t],
        "pressure":   [1.5 + random.gauss(0, 0.02) for _ in t],
        "accel_x":    [random.gauss(0, 0.01) for _ in t],
        "accel_y":    [random.gauss(0, 0.01) for _ in t],
        "accel_z":    [1.0 + random.gauss(0, 0.01) for _ in t],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("BACKEND_URL", "http://localhost:8080"),
                    help="Backend base URL (default: localhost:8080)")
    ap.add_argument("--device", default="GLUCO001")
    ap.add_argument("--subject", default="S007")
    ap.add_argument("--fw", default="1.0.0")
    args = ap.parse_args()

    api_key = os.environ.get("DEVICE_API_KEY", "dev-only-change-me")
    if api_key == "dev-only-change-me":
        print("[warn] Using default DEVICE_API_KEY — set the env var to match your backend.",
              file=sys.stderr)

    payload = {
        "device_id": args.device,
        "subject_pseudonym": args.subject,
        "capture_uuid": str(uuid.uuid4()),
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "fw_version": args.fw,
        "sampling_rate_hz": SAMPLE_RATE_HZ,
        "window_seconds": N_SAMPLES // SAMPLE_RATE_HZ,
        "sensors": synthesise_window(),
        "device_meta": {"battery_pct": 88},
    }

    body = json.dumps(payload).encode()
    endpoint = args.url.rstrip("/") + "/api/ingest/window"
    req = urllib.request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )

    print(f"POST {endpoint}  (payload {len(body)//1024} KB)")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            dt = time.time() - t0
            print(f"[ok] HTTP {resp.status} in {dt:.2f}s")
            print(json.dumps(json.loads(resp.read()), indent=2))
    except urllib.error.HTTPError as e:
        dt = time.time() - t0
        print(f"[fail] HTTP {e.code} in {dt:.2f}s")
        print(e.read().decode(errors="replace"))
        return 1
    except Exception as e:
        print(f"[fail] {type(e).__name__}: {e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
