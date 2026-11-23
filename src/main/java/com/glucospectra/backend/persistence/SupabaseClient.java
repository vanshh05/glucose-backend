package com.glucospectra.backend.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Supabase's PostgREST endpoint.
 *
 * Why REST and not JDBC:
 *   - No connection-pool tuning
 *   - Uses the same service-role key we already have
 *   - Row-level security policies apply uniformly
 *   - Works the same from dev laptop and Render free tier
 *
 * The service_role key bypasses RLS — treat as a server secret. Never log it,
 * never ship it in any response body.
 *
 * Only registered when glucose.supabase.enabled=true, so local dev without a
 * SUPABASE_URL set can still run the backend end-to-end.
 */
@Component
@ConditionalOnProperty(name = "glucose.supabase.enabled", havingValue = "true")
public class SupabaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseClient.class);

    private final WebClient webClient;
    private final String restUrl;
    private final String serviceKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public SupabaseClient(
            @Value("${glucose.supabase.url}") String supabaseUrl,
            @Value("${glucose.supabase.service-key}") String serviceKey
    ) {
        if (supabaseUrl == null || supabaseUrl.isBlank()
                || serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                    "SUPABASE_URL and SUPABASE_SERVICE_KEY must be set when glucose.supabase.enabled=true");
        }
        this.restUrl = supabaseUrl.replaceAll("/$", "") + "/rest/v1";
        this.serviceKey = serviceKey;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
        log.info("Supabase client ready at {}", restUrl);
    }

    /**
     * Insert one or more rows into a table.
     *
     * Uses Prefer: resolution=merge-duplicates so a retry of the same capture
     * doesn't 409 — the schema's unique constraints catch dupes gracefully.
     */
    public void insert(String table, List<Map<String, Object>> rows) {
        try {
            String body = mapper.writeValueAsString(rows);
            webClient.post()
                    .uri(restUrl + "/" + table)
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume(e -> {
                        log.warn("Supabase insert into {} failed: {}", table, e.getMessage());
                        return reactor.core.publisher.Mono.just("");
                    })
                    .block();
        } catch (Exception e) {
            log.warn("Supabase insert into {} failed: {}", table, e.toString());
        }
    }

    /**
     * Fetch rows filtered by a single eq-condition. Used to resolve the
     * subject's UUID from their pseudonym, and the device's UUID from its code.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> selectEq(String table, String column, String value, String select) {
        try {
            String uri = UriComponentsBuilder.fromUriString(restUrl + "/" + table)
                    .queryParam(column, "eq." + value)
                    .queryParam("select", select)
                    .build(true)
                    .toUriString();

            List<Map<String, Object>> rows = webClient.get()
                    .uri(uri)
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("Supabase select from {} failed: {}", table, e.toString());
            return List.of();
        }
    }
}
