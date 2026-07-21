package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;

/**
 * Runs the 100+3-factor box-office prediction prompt against historical Indian movies in
 * {@code movies_data_collection} and checks the prediction against the actual gross recorded for
 * each, to gauge how close the prompt's predictions land to reality.
 */
public interface BoxOfficeBacktestService {

    /**
     * Selects up to {@code limit} eligible movies (Indian-language, released after 2000, with at
     * least one actual-gross figure to validate against), then kicks off prediction + validation
     * for all of them on a background thread. Returns immediately once the movie set is known —
     * the returned status's counts will still be climbing toward {@code totalMovies}.
     *
     * @param limit max movies to process; defaults to 50 if null or non-positive.
     */
    BacktestRunStatus startRun(Integer limit);

    /** Looks up a previously started run by id, or {@code null} if no such run is known. */
    BacktestRunStatus getStatus(String runId);
}
