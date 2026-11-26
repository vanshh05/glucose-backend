package com.glucospectra.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Simple shared-secret auth for the ingest endpoint.
 *
 * ESP32 must send: Authorization: Bearer <DEVICE_API_KEY>
 *
 * Health endpoint is public so load balancers and uptime monitors can probe it.
 *
 * This is deliberately simple for the research phase. For production, swap for
 * per-device JWTs (one signing key, device registers once, device holds its
 * short-lived token). That change is one filter, no controller churn.
 */
@Component
public class DeviceAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthFilter.class);

    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceAuthFilter(@Value("${glucose.security.device-api-key}") String apiKey) {
        this.apiKey = apiKey;
        if ("dev-only-change-me".equals(apiKey)) {
            log.warn("DEVICE_API_KEY not set — using dev default. Do NOT ship this.");
        }
    }

    @Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // Health probes stay open.
    // CORS preflight OPTIONS requests bypass auth — they carry no body and
    // no Authorization header, and must be answered by Spring's CORS filter
    // so the browser can decide whether to send the real request.
    return path.endsWith("/health")
            || path.equals("/")
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
}

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || !apiKey.equals(auth.substring(7).trim())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            mapper.writeValue(response.getWriter(), Map.of(
                    "error", "unauthorized",
                    "message", "missing or invalid Authorization header"
            ));
            return;
        }
        chain.doFilter(request, response);
    }
}
