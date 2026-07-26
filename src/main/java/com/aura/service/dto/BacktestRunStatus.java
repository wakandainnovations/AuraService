package com.aura.service.dto;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live/completed state of one box-office backtest run, held in-memory by
 * {@code BoxOfficeBacktestServiceImpl} and polled via the controller. {@code processedCount} etc.
 * are updated from the background {@code @Async} thread while a request thread reads them
 * concurrently, hence the atomics/concurrent collection rather than plain fields.
 */
public class BacktestRunStatus {

    public enum State { RUNNING, COMPLETED, FAILED }

    private final String runId;
    private final int totalMovies;
    private final Instant startedAt;
    private final String logFilePath;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger validatedCount = new AtomicInteger(0);
    private final AtomicInteger withinRangeCount = new AtomicInteger(0);
    private final List<BoxOfficeBacktestResult> results = new CopyOnWriteArrayList<>();

    private volatile State state = State.RUNNING;
    private volatile Instant completedAt;
    private volatile String errorMessage;
    private volatile List<BoxOfficeFactorStat> factorSummary = List.of();

    public BacktestRunStatus(String runId, int totalMovies, String logFilePath) {
        this.runId = runId;
        this.totalMovies = totalMovies;
        this.logFilePath = logFilePath;
        this.startedAt = Instant.now();
    }

    public void recordResult(BoxOfficeBacktestResult result) {
        results.add(result);
        processedCount.incrementAndGet();
        if (result.error() == null && result.actualGrossUsd() != null && result.predictedGrossUsd() != null) {
            validatedCount.incrementAndGet();
            if (Boolean.TRUE.equals(result.withinTolerance())) {
                withinRangeCount.incrementAndGet();
            }
        }
    }

    public void complete(List<BoxOfficeFactorStat> factorSummary) {
        this.factorSummary = factorSummary;
        this.state = State.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.state = State.FAILED;
        this.completedAt = Instant.now();
    }

    public String getRunId() { return runId; }
    public int getTotalMovies() { return totalMovies; }
    public Instant getStartedAt() { return startedAt; }
    public String getLogFilePath() { return logFilePath; }
    public int getProcessedCount() { return processedCount.get(); }
    public int getValidatedCount() { return validatedCount.get(); }
    public int getWithinRangeCount() { return withinRangeCount.get(); }
    public List<BoxOfficeBacktestResult> getResults() { return results; }
    public State getState() { return state; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public List<BoxOfficeFactorStat> getFactorSummary() { return factorSummary; }
}
