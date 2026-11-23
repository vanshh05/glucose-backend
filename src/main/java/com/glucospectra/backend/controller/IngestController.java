package com.glucospectra.backend.controller;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.IngestResponse;
import com.glucospectra.backend.service.PredictionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The ingest endpoint the ESP32 (or any client) POSTs to.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final PredictionService predictionService;

    public IngestController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * POST /api/ingest/window
     *
     * Accepts a device capture, forwards to the model, returns the prediction.
     */
    @PostMapping(value = "/window", consumes = "application/json", produces = "application/json")
    public ResponseEntity<IngestResponse> ingestWindow(@Valid @RequestBody DeviceCapturePayload payload) {
        IngestResponse response = predictionService.process(payload);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/ingest/health
     *
     * Trivial liveness probe. Doesn't touch the model.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "glucose-backend",
                "time", java.time.Instant.now().toString()
        );
    }
}
