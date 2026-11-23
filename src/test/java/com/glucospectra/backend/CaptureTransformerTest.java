package com.glucospectra.backend;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.ModelRequest;
import com.glucospectra.backend.service.CaptureTransformer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the device → model reshape preserves channels and sampling rate,
 * and that recording_id encodes device + capture uuid for traceability.
 */
class CaptureTransformerTest {

    private static final List<String> CHANNELS = List.of(
            "green_530", "red_660", "nir_940", "dark_signal",
            "skin_temp", "pressure", "accel_x", "accel_y", "accel_z"
    );

    @Test
    void reshapesIntoModelFormat() {
        CaptureTransformer t = new CaptureTransformer(CHANNELS);

        Map<String, List<Double>> sensors = new LinkedHashMap<>();
        for (String c : CHANNELS) {
            sensors.put(c, List.of(0.1, 0.2, 0.3));
        }
        // Add an extra channel the model doesn't want — must be dropped.
        sensors.put("extra_channel", List.of(9.9));

        DeviceCapturePayload capture = new DeviceCapturePayload(
                "GLUCO001",
                "S007",
                "9f2c",
                Instant.parse("2026-11-23T10:15:03Z"),
                "1.0.0",
                100,
                30,
                sensors,
                Map.of("battery_pct", 88)
        );

        ModelRequest req = t.toModelRequest(capture);

        assertEquals("GLUCO001_9f2c", req.recordingId());
        assertEquals(100, req.samplingRateHz());
        assertEquals(CHANNELS.size(), req.sensors().size(), "should be exactly 9 channels");
        assertTrue(req.sensors().keySet().containsAll(CHANNELS));
        assertEquals(List.of(0.1, 0.2, 0.3), req.sensors().get("green_530"));
        assertTrue(!req.sensors().containsKey("extra_channel"), "extra channels must be dropped");
    }
}
