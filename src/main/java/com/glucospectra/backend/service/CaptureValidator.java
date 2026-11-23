package com.glucospectra.backend.service;

import com.glucospectra.backend.dto.DeviceCapturePayload;
import com.glucospectra.backend.exception.InvalidCaptureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates an incoming capture against the shape the model needs.
 *
 * Bean validation (@NotBlank etc.) handled the required top-level fields.
 * This covers the rules those annotations can't express — sensor channels
 * and array lengths.
 */
@Component
public class CaptureValidator {

    private final int expectedSamples;
    private final int expectedSamplingRateHz;
    private final List<String> expectedChannels;

    public CaptureValidator(
            @Value("${glucose.ingest.expected-samples}") int expectedSamples,
            @Value("${glucose.ingest.expected-sampling-rate-hz}") int expectedSamplingRateHz,
            @Value("${glucose.ingest.expected-channels}") List<String> expectedChannels
    ) {
        this.expectedSamples = expectedSamples;
        this.expectedSamplingRateHz = expectedSamplingRateHz;
        this.expectedChannels = expectedChannels;
    }

    public void validate(DeviceCapturePayload p) {
        // Sampling rate must match what the model was trained on.
        if (p.samplingRateHz() != expectedSamplingRateHz) {
            throw new InvalidCaptureException(
                    "sampling_rate_hz must be %d (got %d)".formatted(expectedSamplingRateHz, p.samplingRateHz())
            );
        }

        Map<String, List<Double>> sensors = p.sensors();
        if (sensors == null) {
            throw new InvalidCaptureException("sensors block is missing");
        }

        // All expected channels must be present...
        for (String channel : expectedChannels) {
            List<Double> samples = sensors.get(channel);
            if (samples == null) {
                throw new InvalidCaptureException("missing sensor channel: " + channel);
            }
            // ...with exactly the right number of samples.
            if (samples.size() != expectedSamples) {
                throw new InvalidCaptureException(
                        "channel %s has %d samples, expected %d"
                                .formatted(channel, samples.size(), expectedSamples)
                );
            }
            // No nulls inside the array — would corrupt the model input.
            if (samples.contains(null)) {
                throw new InvalidCaptureException("channel %s contains null samples".formatted(channel));
            }
        }

        // We don't fail if the device sent EXTRA channels — the transformer just drops them.
    }
}
