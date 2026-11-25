import json
import math
from pathlib import Path

import numpy as np

SAMPLE_RATE_HZ = 100
DURATION_SECONDS = 30
SAMPLE_COUNT = SAMPLE_RATE_HZ * DURATION_SECONDS

rng = np.random.default_rng(123)
time = np.arange(SAMPLE_COUNT) / SAMPLE_RATE_HZ
pulse = np.sin(2 * math.pi * 1.2 * time)

sensors = {
    "green_530": (1.0 + 0.03 * pulse + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
    "red_660": (0.8 + 0.025 * pulse + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
    "nir_940": (1.2 + 0.02 * pulse + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
    "dark_signal": (0.02 + rng.normal(0, 0.003, SAMPLE_COUNT)).tolist(),
    "skin_temp": (33.0 + rng.normal(0, 0.05, SAMPLE_COUNT)).tolist(),
    "pressure": (1.5 + rng.normal(0, 0.02, SAMPLE_COUNT)).tolist(),
    "accel_x": (0.01 * np.sin(time) + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
    "accel_y": (0.01 * np.cos(time) + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
    "accel_z": (1.0 + rng.normal(0, 0.005, SAMPLE_COUNT)).tolist(),
}

request_data = {
    "recording_id": "prediction_demo_001",
    "sampling_rate_hz": SAMPLE_RATE_HZ,
    "sensors": sensors,
}

output_path = Path(__file__).resolve().with_name("prediction_demo_request.json")
output_path.write_text(
    json.dumps(request_data, separators=(",", ":")),
    encoding="utf-8",
)
print(f"Saved synthetic demo request to: {output_path}")
print("This is test data, not a real glucose measurement.")