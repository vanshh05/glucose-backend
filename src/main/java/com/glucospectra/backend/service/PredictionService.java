package com.glucospectra.backend.service;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.IngestResponse;
import com.glucospectra.backend.dto.ModelRequest;
import com.glucospectra.backend.dto.ModelResponse;
import com.glucospectra.backend.persistence.CaptureCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Orchestration: validate → transform → model call → clean response.
 *
 * Publishes a CaptureCompletedEvent after each successful prediction so the
 * persistence layer (or anyone else — webhooks, analytics) can hook in
 * without touching this flow.
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    private final CaptureValidator validator;
    private final CaptureTransformer transformer;
    private final ModelClient modelClient;
    private final ApplicationEventPublisher events;

    public PredictionService(CaptureValidator validator,
                             CaptureTransformer transformer,
                             ModelClient modelClient,
                             ApplicationEventPublisher events) {
        this.validator = validator;
        this.transformer = transformer;
        this.modelClient = modelClient;
        this.events = events;
    }

    public IngestResponse process(DeviceCapturePayload capture) {
        log.info("Processing capture — device={} uuid={} subject={}",
                capture.deviceId(), capture.captureUuid(), capture.subjectPseudonym());

        // 1. Reject malformed captures early.
        validator.validate(capture);

        // 2. Reshape for the model.
        ModelRequest modelReq = transformer.toModelRequest(capture);

        // 3. Call the model.
        ModelResponse modelResp = modelClient.predict(modelReq);

        // 4. Build the clean response.
        Double glucose = extractGlucose(modelResp.raw());
        IngestResponse response = new IngestResponse(
                capture.captureUuid(),
                modelReq.recordingId(),
                Instant.now(),
                glucose,
                modelResp.raw()
        );

        // 5. Fire-and-forget event for persistence / downstream consumers.
        //    An async listener handles the actual write; this call doesn't block.
        events.publishEvent(new CaptureCompletedEvent(capture, response));

        return response;
    }

    /**
     * Best-effort parse of the glucose number. The model team can change field
     * names without breaking us — we check the current and likely future ones.
     */
    private Double extractGlucose(Map<String, Object> raw) {
        if (raw == null) return null;
        for (String key : new String[]{
                "predicted_glucose_mg_dl",  // current live model
                "glucose_mgdl",
                "predicted_mgdl",
                "glucose",
                "prediction"
        }) {
            Object v = raw.get(key);
            if (v instanceof Number n) return n.doubleValue();
        }
        return null;
    }
}
