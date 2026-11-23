package com.glucospectra.backend.persistence;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.IngestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for CaptureCompletedEvent and writes the full row-set to Supabase.
 *
 * Runs on a separate thread (@Async) so persistence latency never slows the
 * HTTP response to the ESP32.
 *
 * Writes four tables, in order:
 *   1. sessions          — the capture metadata (parent row)
 *   2. raw_windows       — the per-channel sample arrays (immutable blob)
 *   3. covariates        — temp, pressure, motion summary
 *   4. predictions       — the model's output, tagged with model_version
 *
 * On failure we log and move on — persistence is best-effort, never blocks the
 * caller. Rows that fail can be replayed later from the ingest_log.
 *
 * Only active when glucose.supabase.enabled=true; otherwise the class is not
 * created and the event dies on the event bus with no subscribers.
 */
@Component
@ConditionalOnProperty(name = "glucose.supabase.enabled", havingValue = "true")
public class PersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(PersistenceListener.class);

    private final SupabaseClient supabase;

    public PersistenceListener(SupabaseClient supabase) {
        this.supabase = supabase;
    }

    @Async
    @EventListener
    public void onCaptureCompleted(CaptureCompletedEvent event) {
        DeviceCapturePayload capture = event.capture();
        IngestResponse response = event.response();
        long started = System.currentTimeMillis();

        try {
            // ─── 1. Resolve subject + device UUIDs ────────────────────────────
            String subjectId = lookupSubjectId(capture.subjectPseudonym());
            String deviceId = lookupDeviceId(capture.deviceId());
            if (subjectId == null || deviceId == null) {
                log.warn("Skipping persistence — unknown subject ({}) or device ({})",
                        capture.subjectPseudonym(), capture.deviceId());
                logIngest(capture, "rejected", "unknown subject or device");
                return;
            }

            // ─── 2. Insert the session row ────────────────────────────────────
            String sessionId = UUID.randomUUID().toString();
            Map<String, Object> session = new HashMap<>();
            session.put("id", sessionId);
            session.put("subject_id", subjectId);
            session.put("device_id", deviceId);
            session.put("capture_uuid", capture.captureUuid());
            session.put("captured_at", capture.capturedAt().toString());
            session.put("fw_version", capture.fwVersion());
            session.put("sample_rate_hz", capture.samplingRateHz());
            session.put("window_seconds", capture.windowSeconds());
            session.put("pga_gain", 4);
            session.put("led_currents_ma", Map.of("530", 12, "660", 10, "940", 20));
            session.put("fasting_state", "unknown");
            session.put("quality_ok", true);
            supabase.insert("sessions", List.of(session));

            // ─── 3. Insert raw_windows rows (one per channel) ─────────────────
            // These are IMMUTABLE — once written, never changed, so a later
            // improved model can re-process them.
            List<Map<String, Object>> rawRows = new java.util.ArrayList<>();
            for (String channel : List.of("green_530", "red_660", "nir_940", "dark_signal")) {
                List<Double> samples = capture.sensors().get(channel);
                if (samples == null) continue;
                // Map the model's channel names to the schema's channel enum
                String channelKey = switch (channel) {
                    case "green_530"   -> "green530";
                    case "red_660"     -> "red660";
                    case "nir_940"     -> "ir940";
                    case "dark_signal" -> "dark";
                    default            -> channel;
                };
                rawRows.add(Map.of(
                        "session_id", sessionId,
                        "channel", channelKey,
                        "samples", samples,
                        "sample_count", samples.size()
                ));
            }
            if (!rawRows.isEmpty()) {
                supabase.insert("raw_windows", rawRows);
            }

            // ─── 4. Insert covariates (summary stats, not arrays) ─────────────
            Map<String, Object> cov = new HashMap<>();
            cov.put("session_id", sessionId);
            cov.put("skin_temp_c", mean(capture.sensors().get("skin_temp")));
            cov.put("pressure_raw", (int) Math.round(mean(capture.sensors().get("pressure")) * 100));
            cov.put("motion_index_max", motionMax(capture.sensors()));
            cov.put("perfusion_index", perfusionIndex(capture.sensors().get("green_530")));
            Object battery = capture.deviceMeta() == null ? null : capture.deviceMeta().get("battery_pct");
            if (battery instanceof Number n) cov.put("battery_pct", n.intValue());
            supabase.insert("covariates", List.of(cov));

            // ─── 5. Insert the prediction (if the model gave us one) ──────────
            if (response.glucoseMgdl() != null) {
                Map<String, Object> pred = new HashMap<>();
                pred.put("session_id", sessionId);
                pred.put("model_version", extractModelVersion(response));
                pred.put("glucose_mgdl", response.glucoseMgdl());
                pred.put("ci_low", Math.max(40.0, response.glucoseMgdl() - 15));
                pred.put("ci_high", Math.min(400.0, response.glucoseMgdl() + 15));
                pred.put("calibration_applied", false);
                supabase.insert("predictions", List.of(pred));
            }

            logIngest(capture, "accepted", null);
            log.info("Persisted capture {} in {} ms", capture.captureUuid(),
                    System.currentTimeMillis() - started);

        } catch (Exception e) {
            log.error("Persistence failed for capture {}: {}",
                    capture.captureUuid(), e.toString());
            logIngest(capture, "error", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private String lookupSubjectId(String pseudonym) {
        List<Map<String, Object>> rows = supabase.selectEq("subjects", "pseudonym", pseudonym, "id");
        return rows.isEmpty() ? null : rows.get(0).get("id").toString();
    }

    private String lookupDeviceId(String deviceCode) {
        List<Map<String, Object>> rows = supabase.selectEq("devices", "device_code", deviceCode, "id");
        return rows.isEmpty() ? null : rows.get(0).get("id").toString();
    }

    private void logIngest(DeviceCapturePayload capture, String status, String reason) {
        try {
            Map<String, Object> row = new HashMap<>();
            row.put("device_code", capture.deviceId());
            row.put("capture_uuid", capture.captureUuid());
            row.put("status", status);
            if (reason != null) row.put("reason", reason);
            row.put("received_at", Instant.now().toString());
            supabase.insert("ingest_log", List.of(row));
        } catch (Exception ignored) {
            // Logging the log failing is pure noise — move on.
        }
    }

    private String extractModelVersion(IngestResponse response) {
        if (response.modelRaw() == null) return "unknown";
        Object v = response.modelRaw().get("model_version");
        return v == null ? "unknown" : v.toString();
    }

    private double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double sum = 0;
        for (Double x : xs) sum += x;
        return sum / xs.size();
    }

    /**
     * Per-channel AC magnitude relative to DC — a rough perfusion index.
     * The dashboard treats this as a data-quality signal, not a clinical value.
     */
    private double perfusionIndex(List<Double> samples) {
        if (samples == null || samples.size() < 2) return 0.0;
        double dc = mean(samples);
        if (dc == 0) return 0.0;
        double maxDev = 0;
        for (Double v : samples) maxDev = Math.max(maxDev, Math.abs(v - dc));
        return Math.round((maxDev / dc) * 1000.0) / 1000.0;
    }

    /**
     * Max |accel| magnitude during the window. The dashboard flags readings
     * where this is unusually high as motion-corrupted.
     */
    private double motionMax(Map<String, List<Double>> sensors) {
        List<Double> x = sensors.get("accel_x");
        List<Double> y = sensors.get("accel_y");
        List<Double> z = sensors.get("accel_z");
        if (x == null || y == null || z == null) return 0.0;
        int n = Math.min(x.size(), Math.min(y.size(), z.size()));
        double max = 0;
        for (int i = 0; i < n; i++) {
            double ax = x.get(i), ay = y.get(i), az = z.get(i) - 1.0; // subtract 1g gravity
            double mag = Math.sqrt(ax * ax + ay * ay + az * az);
            if (mag > max) max = mag;
        }
        return Math.round(max * 1000.0) / 1000.0;
    }
}
