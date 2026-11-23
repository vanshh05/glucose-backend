# glucose-backend

Spring Boot service that sits between the ESP32 and the GCNN model endpoint.

```
  ESP32  ─── POST /api/ingest/window ──►  glucose-backend
                                                │
                                                │ reshapes payload
                                                ▼
                        https://glucospectra-gcnn.onrender.com/predict
                                                │
                                                │ returns prediction
                                                ▼
  ESP32  ◄─── IngestResponse ──────────  glucose-backend
```

This phase is deliberately **stateless** — no Supabase writes, no dashboards, no
persistence. The single job is: receive capture, reshape, forward, return.
Persistence is a separate layer we'll bolt on next without touching this flow.

## Run locally

```bash
# Requires Java 21 + Maven
cd glucose-backend
./mvnw spring-boot:run
# → http://localhost:8080
```

Or configure in `application.yml`:

```yaml
glucose:
  model:
    endpoint: https://glucospectra-gcnn.onrender.com/predict
  security:
    device-api-key: <your-shared-secret>
```

Env-var overrides for anything you don't want in code:

| Variable | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `MODEL_ENDPOINT` | the live URL | swap for staging / mocks |
| `MODEL_TIMEOUT_SECONDS` | `60` | Render cold starts eat ~30s |
| `MODEL_RETRIES` | `1` | retried only on 5xx / timeout |
| `DEVICE_API_KEY` | `dev-only-change-me` | **set this before deploying** |

## Endpoints

### `POST /api/ingest/window`

Requires `Authorization: Bearer <DEVICE_API_KEY>`.

Request body (what the ESP32 sends):

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
  "modelRaw": { /* raw model response for diagnostics */ }
}
```

HTTP codes you can get back:

| Code | Meaning | Device should... |
|---|---|---|
| `200` | prediction returned | use it |
| `400` | malformed JSON / missing field | fix firmware |
| `401` | wrong or missing API key | fix firmware |
| `422` | wrong sampling rate, wrong channel count, etc. | fix firmware |
| `502` | model endpoint failed (5xx / timeout) | retry in ~30s |
| `500` | something unexpected on our side | log and retry |

### `GET /api/ingest/health`

Public (no auth). Returns `{"status":"ok", ...}`. For uptime monitors.

## Testing without an ESP32

```bash
# Start the backend in one terminal
./mvnw spring-boot:run

# In another, fire a fake capture
DEVICE_API_KEY=dev-only-change-me python3 scripts/send_fake_window.py
```

You'll see the request posted, the model called, and the response printed.
First call may take 30-60s because of Render cold start — subsequent calls
should be a few seconds.

## Deploying

Any Java 21 runtime works. Three good options:

- **Render** (same host as the model) — closest latency, free tier is fine
- **Railway** — single `Dockerfile` or detect-Java build
- **Fly.io** — generous free tier, good for low-traffic India-hosted services

Set these env vars at minimum: `MODEL_ENDPOINT`, `DEVICE_API_KEY`. Bump
`PORT` if your host expects something other than 8080.

## What's next (not in this build)

- **Persistence** — write each capture + prediction to Supabase using the
  schema we already have. One `@Component` that subscribes to prediction
  events, no churn in the current flow.
- **Per-device JWTs** — replace the shared-secret auth filter.
- **Rate limiting** — one capture per device every N seconds, Bucket4j.
- **Structured observability** — Micrometer + OTEL → wherever metrics go.
