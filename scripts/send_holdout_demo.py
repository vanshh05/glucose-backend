#!/usr/bin/env python3
"""
Posts the model team's own holdout demo payload through the backend.

Unlike send_fake_window.py (which synthesizes a window), this reads a REAL
model-format payload file (recording_id + sampling_rate_hz + sensors) and
wraps it in our device-layer envelope (device_id, subject, capture_uuid, etc.)
before posting to the backend.

Use this to prove end-to-end that the pipeline works with input the model
is guaranteed to handle, independent of our synthetic-signal quality.

Usage:
    # Point at the backend URL and the model's demo JSON:
    python scripts/send_holdout_demo.py \
        --url https://glucose-backend-llnb.onrender.com \
        --payload path\to\demo_prediction_request.json

Needs env var DEVICE_API_KEY (must match the backend's config).
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timezone


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("BACKEND_URL", "http://localhost:8080"))
    ap.add_argument("--payload", required=True,
                    help="Path to the model-format JSON (recording_id/sampling_rate_hz/sensors)")
    ap.add_argument("--device", default="GLUCO001")
    ap.add_argument("--subject", default="S007")
    ap.add_argument("--fw", default="1.0.0")
    args = ap.parse_args()

    api_key = os.environ.get("DEVICE_API_KEY", "dev-only-change-me")
    if api_key == "dev-only-change-me":
        print("[warn] Using default DEVICE_API_KEY — set the env var to match your backend.",
              file=sys.stderr)

    # Load the model-format payload
    with open(args.payload, "r", encoding="utf-8") as f:
        model_payload = json.load(f)

    sensors = model_payload["sensors"]
    first_channel = next(iter(sensors.values()))
    n_samples = len(first_channel)
    sample_rate = model_payload["sampling_rate_hz"]
    window_seconds = round(n_samples / sample_rate)

    # Wrap it in the backend's expected device envelope
    wrapped = {
        "device_id": args.device,
        "subject_pseudonym": args.subject,
        "capture_uuid": str(uuid.uuid4()),
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "fw_version": args.fw,
        "sampling_rate_hz": sample_rate,
        "window_seconds": window_seconds,
        "sensors": sensors,
        "device_meta": {"battery_pct": 88, "source": "holdout_demo"},
    }

    body = json.dumps(wrapped).encode()
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
    print(f"      original recording_id: {model_payload.get('recording_id')}")
    print(f"      wrapped capture_uuid:  {wrapped['capture_uuid']}")
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
