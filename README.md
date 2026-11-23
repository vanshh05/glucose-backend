# glucose-backend

Spring Boot service that sits between the ESP32 and the GCNN model endpoint,
with optional write-through to Supabase so every capture lands in the dashboard.

```
  ESP32  ─── POST /api/ingest/window ──►  glucose-backend
                                                │
                                                │ 1. validate + reshape
                                                ▼
                        https://glucospectra-gcnn.onrender.com/predict
                                                │
                                                │ 2. returns prediction
                                                ▼
  ESP32  ◄─── IngestResponse ──────────  glucose-backend
                                                │
                                                │ 3. fires CaptureCompletedEvent
                                                ▼
                                       PersistenceListener  (@Async)
                                                │
                                                ▼
                               Supabase: sessions, raw_windows,
                                         covariates, predictions
```

The persistence step happens on a separate thread, so the HTTP response is
already on its way back to the ESP32 before any database writes happen.

## Run locally

```bash
# Requires Java 17 or 21 + Maven
cd glucose-backend
./mvnw spring-boot:run
# → http://localhost:8080
```

## Config

Everything is env-var overridable:

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `MODEL_ENDPOINT` | `https://glucospectra-gcnn.onrender.com/predict` | swap for staging / mocks |
| `MODEL_TIMEOUT_SECONDS` | `60` | Render cold starts eat ~30s |
| `MODEL_RETRIES` | `1` | retried only on 5xx / timeout |
| `DEVICE_API_KEY` | `dev-only-change-me` | **set before deploying** |
| `SUPABASE_ENABLED` | `false` | flip to `true` for write-through |
| `SUPABASE_URL` | — | `https://<ref>.supabase.co` |
| `SUPABASE_SERVICE_KEY` | — | **service_role** key, never ship to browser |

## Endpoints

### `POST /api/ingest/window`

Requires `Authorization: Bearer <DEVICE_API_KEY>`.

Request body — what the ESP32 sends (full 30-second window):

```json
{
  "device_id": "GLUCO001",
  "subject_pseudonym": "S007",
  "capture_uuid": "9f2c-4a1b-...",
  "captured_at": "2026-11-23T10:15:03Z",
  "fw_version": "1.0.0",
  "sampling_rate_hz": 100,
  "window_seconds": 30,
  "sensors": {
    "green_530":   [/* 3000 floats */],
    "red_660":     [/* 3000 floats */],
    "nir_940":     [/* 3000 floats */],
    "dark_signal": [/* 3000 floats */],
    "skin_temp":   [/* 3000 floats */],
    "pressure":    [/* 3000 floats */],
    "accel_x":     [/* 3000 floats */],
    "accel_y":     [/* 3000 floats */],
    "accel_z":     [/* 3000 floats */]
  },
  "device_meta": { "battery_pct": 88 }
}
```

Response:

```json
{
  "captureUuid": "9f2c-4a1b-...",
  "recordingId": "GLUCO001_9f2c-4a1b-...",
  "processedAt": "2026-11-23T10:15:09Z",
  "glucoseMgdl": 118.4,
  "modelRaw": { ... }
}
```

Status codes:

| Code | Meaning | Device should... |
|---|---|---|
| `200` | prediction returned | use it |
| `400` | malformed JSON / missing field | fix firmware |
| `401` | wrong or missing API key | fix firmware |
| `422` | wrong sampling rate or channel count | fix firmware |
| `502` | model endpoint failed (5xx / timeout) | retry in ~30s |

### `GET /api/ingest/health`

Public, no auth. For UptimeRobot-style monitors and Render keep-warm pings.

## Supabase write-through

When `SUPABASE_ENABLED=true`, every successful capture fires a background
event that writes to four tables in your existing `glucose-monitor-dev`
project:

| Table | What lands here |
|---|---|
| `sessions` | capture metadata — subject, device, timestamps, firmware |
| `raw_windows` | the 4 optical sample arrays (immutable, for later reprocessing) |
| `covariates` | temp mean, pressure mean, motion max, perfusion index |
| `predictions` | the model's glucose number + CI band + model version |

Additionally, every ingest attempt writes a row to `ingest_log` for audit.

### Prerequisites in Supabase

Before enabling persistence, the device and subject referenced in the payload
must exist in Supabase:

```sql
-- One-time: add your physical device
insert into public.devices (device_code, hw_revision, fw_version)
values ('GLUCO001', 'rev-A', '1.0.0')
on conflict (device_code) do nothing;

-- One-time per subject (you likely already have S007 from the seed)
insert into public.subjects (pseudonym, age_band, fitzpatrick, sex_at_birth)
values ('S007', '18-24', 2, 'F')
on conflict (pseudonym) do nothing;
```

If either lookup fails, the backend logs a warning and still returns the
prediction to the ESP32 — persistence is never allowed to break the live flow.

### How Supabase failures are handled

- **Supabase down / slow**: write is retried once, then logged and dropped.
  The ESP32 already has its prediction; the row is lost from the dashboard but
  the prediction itself was real.
- **Duplicate capture_uuid**: schema's unique constraint handles it; we use
  `Prefer: resolution=merge-duplicates` to avoid a 409.
- **Unknown subject or device**: logged as `rejected` in `ingest_log`,
  nothing else is written.

## Testing without an ESP32

```bash
# Start the backend
./mvnw spring-boot:run

# Fire a fake capture
DEVICE_API_KEY=dev-only-change-me python3 scripts/send_fake_window.py
```

First call takes 30–60s because of Render model cold start. Follow-ups ~2s.

With Supabase enabled, each fake capture also lands in the dashboard. Open
`https://glucose-dashboard-mu.vercel.app/subjects/S007` and you'll see the
new reading appear within a few seconds.

## Deploy to Render

Dockerfile already included. Steps:

1. Push to GitHub.
2. On Render: **New** → **Web Service** → pick the repo.
3. Runtime: **Docker** (Render auto-detects the Dockerfile).
4. Add env vars: `DEVICE_API_KEY`, `MODEL_ENDPOINT` (optional, has default),
   `SUPABASE_ENABLED=true`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`.
5. Deploy. First build ~3 min; subsequent ~1 min thanks to Docker layer cache.

Free tier sleeps after 15 min idle — set up UptimeRobot to ping
`/api/ingest/health` every 5 min to keep it warm.

## What's next (not in this build)

- **Operator ground-truth entry page** — small UI for researchers to pair a
  finger-prick reading to the most recent capture.
- **Per-device JWTs** — replace the shared-secret auth.
- **Rate limiting** — one capture per device every N seconds, Bucket4j.
- **Model-raw snapshot in Supabase** — add a `raw_response jsonb` column to
  `predictions` so field renames on the model side stay auditable.
