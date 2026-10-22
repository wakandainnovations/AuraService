package com.aura.service.predictor;

import com.aura.service.currency.ExchangeRateUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Max/safe budget and an OPPORTUNITY/RISK call for a planned movie, from AuraPredictor's
 * Budget_Opportunity_Risk model, with budgets in INR. Any authenticated user (falls under
 * {@code anyRequest().authenticated()} in {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class BudgetRiskController {

    private final BudgetRiskService budgetRiskService;

    @PostMapping("/budget-risk")
    public ResponseEntity<JsonNode> assessBudgetRisk(@Valid @RequestBody BudgetRiskRequest request) {
        return ResponseEntity.ok(budgetRiskService.assess(request));
    }

    // Local handler so GlobalExceptionHandler's catch-all RuntimeException -> 400 doesn't apply.
    @ExceptionHandler(AuraPredictorException.class)
    public ResponseEntity<Map<String, Object>> handleAuraPredictorException(AuraPredictorException ex) {
        return ex.toResponse();
    }

    @ExceptionHandler(ExchangeRateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleExchangeRateUnavailable(ExchangeRateUnavailableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 503);
        body.put("error", "exchange_rate_unavailable");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(503).body(body);
    }
}
