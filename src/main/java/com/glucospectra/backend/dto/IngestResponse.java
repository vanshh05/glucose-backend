package com.glucospectra.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Our clean response to the ESP32 / caller.
 *
 * - captureUuid echoes the input, so the device knows which capture this prediction belongs to
 * - glucoseMgdl pulled from the model response when present, null otherwise
 * - modelRaw keeps the full model payload for diagnostics / dashboard
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestResponse(
        String captureUuid,
        String recordingId,
        Instant processedAt,
        Double glucoseMgdl,
        Map<String, Object> modelRaw
) { }
