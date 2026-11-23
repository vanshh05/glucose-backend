package com.glucospectra.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One place for mapping exceptions to HTTP responses.
 * Rules:
 *   - 400 for malformed JSON / missing required fields (client bug)
 *   - 422 for well-formed JSON that fails our semantic rules (channel count, etc.)
 *   - 502 for upstream model failures the device can retry
 *   - 500 for anything we didn't anticipate (and we log loudly)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "validation_failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "malformed_json", ex.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(InvalidCaptureException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCapture(InvalidCaptureException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_capture", ex.getMessage());
    }

    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<Map<String, Object>> handleModelCall(ModelCallException ex) {
        log.warn("Model call failed: {} (retryable={})", ex.getMessage(), ex.isRetryable());
        HttpStatus status = ex.isRetryable() ? HttpStatus.BAD_GATEWAY : HttpStatus.UNPROCESSABLE_ENTITY;
        return error(status, "model_error", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code,
                "message", message == null ? "" : message,
                "status", status.value(),
                "timestamp", Instant.now().toString()
        ));
    }
}
