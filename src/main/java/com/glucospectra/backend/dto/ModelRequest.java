package com.glucospectra.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Exact JSON shape the model endpoint expects.
 *
 * Mirrors the sample demo_prediction_request.json:
 *   {
 *     "recording_id": "...",
 *     "sampling_rate_hz": 100,
 *     "sensors": {
 *        "green_530": [... 3000 floats ...],
 *        "red_660":   [... 3000 floats ...],
 *        "nir_940":   [... 3000 floats ...],
 *        "dark_signal": [...],
 *        "skin_temp":   [...],
 *        "pressure":    [...],
 *        "accel_x":     [...],
 *        "accel_y":     [...],
 *        "accel_z":     [...]
 *     }
 *   }
 *
 * No device identity, no timestamps — the model is stateless.
 */
public record ModelRequest(

        @JsonProperty("recording_id")
        String recordingId,

        @JsonProperty("sampling_rate_hz")
        int samplingRateHz,

        @JsonProperty("sensors")
        Map<String, List<Double>> sensors
) { }
