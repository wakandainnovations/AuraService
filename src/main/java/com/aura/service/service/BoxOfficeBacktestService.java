package com.aura.service.service;

import com.aura.service.dto.BacktestRunStatus;
import com.aura.service.dto.MovieIdentifier;

import java.util.List;

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

    /**
     * Re-runs the prediction prompt over exactly the same movie set as a prior run (looked up by
     * id, from that run's in-memory results - not persisted, so this only works while the
     * original run's status is still held in this process) — e.g. after editing the prompt
     * catalog's impact ranges, to validate whether the change actually moved predictions closer
     * to reality on a like-for-like comparison rather than a fresh, possibly different sample.
     *
     * @throws com.aura.service.exception.ResourceNotFoundException if {@code originalRunId} is unknown.
     */
    BacktestRunStatus rerun(String originalRunId);

    /**
     * Same idea as {@link #rerun(String)}, but takes the movie set explicitly rather than looking
     * it up from a prior run's in-memory state — for validating a prompt-catalog change against a
     * specific movie set captured before the app restarted to load that change (run state doesn't
     * survive a restart, since it's in-memory only; see {@code BoxOfficeBacktestServiceImpl}).
     * Identifiers that no longer resolve to a row are silently skipped.
     */
    BacktestRunStatus rerunMovies(List<MovieIdentifier> movies);
}
