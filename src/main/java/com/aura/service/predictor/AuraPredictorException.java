package com.aura.service.predictor;

/**
 * A failed call to AuraPredictor. {@code status} is the HTTP status to surface to our own caller:
 * AuraPredictor's 422 (bad input) / 503 (no trained model) pass through as-is, and an unreachable
 * or timed-out upstream becomes 504 - the same code {@code AuraMathProxyService} uses.
 */
public class AuraPredictorException extends RuntimeException {

    private final int status;

    public AuraPredictorException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AuraPredictorException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
