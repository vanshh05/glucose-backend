package com.glucospectra.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the ESP32 (or test client) POSTs to /api/ingest/window.
 *
 * Shape is deliberately a superset of the model's input: it carries device
 * identity and timing metadata that the model doesn't see, plus the raw
 * sensor arrays. The service layer strips/transforms into the model's shape.
 *
 * Unknown fields are ignored so future firmware can add metadata without
 * breaking the backend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceCapturePayload(

        @NotBlank
        @JsonProperty("device_id")
        String deviceId,

        @NotBlank
        @JsonProperty("subject_pseudonym")
        String subjectPseudonym,

        @NotBlank
        @JsonProperty("capture_uuid")
        String captureUuid,

        @NotNull
        @JsonProperty("captured_at")
        Instant capturedAt,

        @JsonProperty("fw_version")
        String fwVersion,

        @NotNull
        @Positive
        @JsonProperty("sampling_rate_hz")
        Integer samplingRateHz,

        @NotNull
        @Positive
        @JsonProperty("window_seconds")
        Integer windowSeconds,

        @NotNull
        @Valid
        @JsonProperty("sensors")
        Map<String, List<Double>> sensors,

        @JsonProperty("device_meta")
        Map<String, Object> deviceMeta
) { }
