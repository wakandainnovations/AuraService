package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.dto.MovieBacktestRow;

import java.util.List;

/**
 * Does the actual (slow) work for a backtest run — one LLM call per movie plus response
 * validation — off the request thread. Split out from {@link BoxOfficeBacktestService} purely so
 * {@code @Async} applies: Spring's async proxy can't intercept a method calling itself within the
 * same bean, so the synchronous "select movies" half and the async "call the LLM for each one"
 * half have to live in different beans.
 */
public interface BoxOfficeBacktestWorker {

    void processAsync(String runId, BacktestRunStatus status, List<MovieBacktestRow> movies);
}
