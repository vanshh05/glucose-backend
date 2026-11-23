package com.glucospectra.backend.persistence;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.IngestResponse;

/**
 * Published by PredictionService the moment the model answers, carries
 * everything the persistence layer needs to write a full row-set.
 *
 * Decoupled on purpose — PredictionService doesn't know or care whether
 * anyone is listening. Tests can run without Supabase.
 */
public record CaptureCompletedEvent(
        DeviceCapturePayload capture,
        IngestResponse response
) { }
