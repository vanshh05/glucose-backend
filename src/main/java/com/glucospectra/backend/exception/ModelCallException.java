package com.glucospectra.backend.exception;

/**
 * Thrown when calling the upstream model endpoint fails.
 * retryable = true for 5xx/timeout, false for 4xx.
 */
public class ModelCallException extends RuntimeException {
    private final boolean retryable;

    public ModelCallException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
}
