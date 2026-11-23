package com.glucospectra.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * Responsibility of this backend:
 *   1. Accept a 30-second capture window from an ESP32 (POST /api/ingest/window)
 *   2. Reshape it into the exact JSON the model endpoint expects
 *   3. Forward to the model, return the prediction to the caller
 *
 * Deliberately stateless for now — persistence to Supabase lives in a later phase.
 */
@SpringBootApplication
public class GlucoseBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(GlucoseBackendApplication.class, args);
    }
}
