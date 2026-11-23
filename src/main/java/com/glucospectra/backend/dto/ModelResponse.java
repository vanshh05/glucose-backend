package com.glucospectra.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The model's response, kept loose with a raw map so field naming on the
 * model side can evolve without a backend change.
 *
 * Expected minimum: a `glucose_mgdl` (or similar) numeric field.
 * The adapter in PredictionService normalises what we return to the caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelResponse(
        Map<String, Object> raw
) { }
