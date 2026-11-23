package com.glucospectra.backend.service;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.IngestResponse;
import com.glucospectra.backend.dto.ModelRequest;
import com.glucospectra.backend.dto.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Orchestration: validate → transform → model call → clean response.
 *
 * Kept intentionally thin. Persistence (Supabase writes) will be added as a
 * separate component without touching this class.
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    private final CaptureValidator validator;
    private final CaptureTransformer transformer;
    private final ModelClient modelClient;

    public PredictionService(CaptureValidator validator,
                             CaptureTransformer transformer,
                             ModelClient modelClient) {
        this.validator = validator;
        this.transformer = transformer;
        this.modelClient = modelClient;
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

        // 4. Pull the glucose number out if the model gave us one, keep the whole blob too.
        Double glucose = extractGlucose(modelResp.raw());

        return new IngestResponse(
                capture.captureUuid(),
                modelReq.recordingId(),
                Instant.now(),
                glucose,
                modelResp.raw()
        );
    }

    /**
     * Best-effort parse of the glucose number. The model team can change field
     * names without breaking us — we check a few likely ones.
     */
    private Double extractGlucose(Map<String, Object> raw) {
        if (raw == null) return null;
        for (String key : new String[]{"glucose_mgdl", "glucose", "prediction", "predicted_mgdl", "predicted_glucose_mg_dl"}) {
            Object v = raw.get(key);
            if (v instanceof Number n) return n.doubleValue();
        }
        return null;
    }
}
