package com.aura.service.controller;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.service.BoxOfficeBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin-only: runs the 100+3-factor box-office prediction prompt against historical Indian movies
 * in {@code movies_data_collection} and checks predictions against actual gross, to validate how
 * close AuraLLM's predictions land to reality. Access is enforced both in {@code SecurityConfig}
 * ({@code /api/admin/**} requires {@code ROLE_ADMIN}) and by {@link PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin/box-office-backtest")
@RequiredArgsConstructor
public class BoxOfficeBacktestController {

    private final BoxOfficeBacktestService backtestService;

    /**
     * Starts a run over up to {@code limit} eligible movies (default 50) and returns immediately;
     * poll {@code GET /{runId}} for progress. Keep {@code limit} small for a first smoke test -
     * each movie is one real call to the AuraLLM gateway.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> startRun(@RequestParam(required = false) Integer limit) {
        BacktestRunStatus status = backtestService.startRun(limit);
        return ResponseEntity.ok(toResponse(status));
    }

    @GetMapping("/{runId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String runId) {
        BacktestRunStatus status = backtestService.getStatus(runId);
        if (status == null) {
            throw new ResourceNotFoundException("No backtest run found with id: " + runId);
        }
        return ResponseEntity.ok(toResponse(status));
    }

    private Map<String, Object> toResponse(BacktestRunStatus status) {
        Map<String, Object> response = new HashMap<>();
        response.put("runId", status.getRunId());
        response.put("state", status.getState());
        response.put("totalMovies", status.getTotalMovies());
        response.put("processedCount", status.getProcessedCount());
        response.put("validatedCount", status.getValidatedCount());
        response.put("withinPredictedRangeCount", status.getWithinRangeCount());
        response.put("startedAt", status.getStartedAt());
        response.put("completedAt", status.getCompletedAt());
        response.put("logFilePath", status.getLogFilePath());
        response.put("errorMessage", status.getErrorMessage());
        response.put("factorSummary", status.getFactorSummary());
        response.put("results", status.getResults());
        return response;
    }
}
