package com.aura.service.predictor;

import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** The error body every AuraPredictor-backed controller returns. */
    public ResponseEntity<Map<String, Object>> toResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", switch (status) {
            case 422 -> "invalid_prediction_input";
            case 503 -> "model_not_available";
            case 504 -> "upstream_unavailable";
            default -> "upstream_failure";
        });
        body.put("message", getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
