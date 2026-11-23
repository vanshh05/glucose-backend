package com.glucospectra.backend.exception;

/**
 * Thrown when the device payload doesn't match the shape the model needs.
 * Mapped to HTTP 422 by GlobalExceptionHandler.
 */
public class InvalidCaptureException extends RuntimeException {
    public InvalidCaptureException(String message) { super(message); }
}
