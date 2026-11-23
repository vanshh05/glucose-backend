package com.glucospectra.backend.service;

import com.glucospectra.backend.dto.ModelRequest;
import com.glucospectra.backend.dto.ModelResponse;
import com.glucospectra.backend.exception.ModelCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;

/**
 * Posts a ModelRequest to the configured endpoint and returns the model's reply.
 *
 * Decisions:
 *   - WebClient (reactive) picked over RestTemplate because Render free tier
 *     cold-starts can be 20-50 seconds — WebClient handles slow calls without
 *     burning a thread.
 *   - Retry only on 5xx / timeout, never on 4xx (those are our bug).
 *   - The whole call is made blocking via .block() because the REST
 *     controller is a plain servlet-stack endpoint. For throughput later,
 *     flip the controller to return Mono<IngestResponse>.
 */
@Component
public class ModelClient {

    private static final Logger log = LoggerFactory.getLogger(ModelClient.class);

    private final WebClient webClient;
    private final String endpoint;
    private final Duration timeout;
    private final int retries;

    public ModelClient(
            @Value("${glucose.model.endpoint}") String endpoint,
            @Value("${glucose.model.timeout-seconds}") int timeoutSeconds,
            @Value("${glucose.model.retries}") int retries
    ) {
        this.endpoint = endpoint;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.retries = retries;

        // Raise the inbound-codec cap so the response doesn't get truncated if
        // the model ever returns a larger payload.
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        log.info("ModelClient configured — endpoint={}, timeout={}s, retries={}",
                endpoint, timeoutSeconds, retries);
    }

    @SuppressWarnings("unchecked")
    public ModelResponse predict(ModelRequest request) {
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> body = webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    // Don't retry on 4xx — those indicate a malformed request from us.
                    .onStatus(
                            status -> status.is4xxClientError(),
                            resp -> resp.bodyToMono(String.class).flatMap(err ->
                                    Mono.error(new ModelCallException(
                                            "model returned %d: %s".formatted(resp.statusCode().value(), err),
                                            false))
                            )
                    )
                    .bodyToMono(Map.class)
                    .timeout(timeout)
                    .retryWhen(Retry.backoff(retries, Duration.ofSeconds(2))
                            .filter(throwable -> throwable instanceof WebClientResponseException wcre
                                    && wcre.getStatusCode().is5xxServerError()
                                    || throwable instanceof java.util.concurrent.TimeoutException)
                            .doBeforeRetry(sig -> log.warn("Retrying model call, attempt {} — reason: {}",
                                    sig.totalRetries() + 1, sig.failure().toString())))
                    .block();

            long took = System.currentTimeMillis() - started;
            log.info("Model call OK in {} ms, recording_id={}", took, request.recordingId());
            return new ModelResponse(body);

        } catch (ModelCallException e) {
            throw e;
        } catch (Exception e) {
            long took = System.currentTimeMillis() - started;
            log.error("Model call failed after {} ms: {}", took, e.toString());
            throw new ModelCallException("model call failed: " + e.getMessage(), true);
        }
    }
}
