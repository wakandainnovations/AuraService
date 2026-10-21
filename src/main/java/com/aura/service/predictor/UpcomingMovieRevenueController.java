package com.aura.service.predictor;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Predicts box-office revenue for a movie that hasn't released yet, using AuraPredictor's CatBoost
 * model. Any authenticated user (falls under {@code anyRequest().authenticated()} in
 * {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class UpcomingMovieRevenueController {

    private final AuraPredictorClient auraPredictorClient;

    @PostMapping("/upcoming-movie")
    public ResponseEntity<UpcomingMovieRevenueResponse> predictUpcomingMovie(
            @Valid @RequestBody UpcomingMovieRevenueRequest request) {
        return ResponseEntity.ok(auraPredictorClient.predictUpcoming(request));
    }

    // Local handler so GlobalExceptionHandler's catch-all RuntimeException -> 400 doesn't apply.
    @ExceptionHandler(AuraPredictorException.class)
    public ResponseEntity<Map<String, Object>> handleAuraPredictorException(AuraPredictorException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatus());
        body.put("error", switch (ex.getStatus()) {
            case 422 -> "invalid_prediction_input";
            case 503 -> "model_not_available";
            case 504 -> "upstream_unavailable";
            default -> "upstream_failure";
        });
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
}
