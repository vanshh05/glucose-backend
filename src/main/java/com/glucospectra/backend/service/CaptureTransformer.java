package com.glucospectra.backend.service;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.dto.ModelRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the device payload into the exact JSON the model wants.
 *
 * Rules:
 *   - recording_id built from device_id + capture_uuid, to stay unique
 *     and traceable back to the capture.
 *   - sampling_rate_hz passed through.
 *   - sensors block is filtered to the 9 expected channels in a fixed order.
 *     Extra channels are dropped; the validator has already confirmed all
 *     required channels are present.
 *
 * Pure function. No side effects, no I/O.
 */
@Component
public class CaptureTransformer {

    private final List<String> expectedChannels;

    public CaptureTransformer(
            @Value("${glucose.ingest.expected-channels}") List<String> expectedChannels
    ) {
        this.expectedChannels = expectedChannels;
    }

    public ModelRequest toModelRequest(DeviceCapturePayload p) {
        // LinkedHashMap preserves channel order — makes debug dumps predictable.
        Map<String, List<Double>> sensors = new LinkedHashMap<>();
        for (String channel : expectedChannels) {
            sensors.put(channel, p.sensors().get(channel));
        }

        String recordingId = "%s_%s".formatted(p.deviceId(), p.captureUuid());

        return new ModelRequest(
                recordingId,
                p.samplingRateHz(),
                sensors
        );
    }
}
