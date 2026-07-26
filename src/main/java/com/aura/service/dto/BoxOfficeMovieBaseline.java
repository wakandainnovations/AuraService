package com.aura.service.dto;

/**
 * The server-computed baseline potential B0 = adjustedBudget * (rStar + rDirector + rConcept) *
 * rIP, before the compounding factor deltas are applied. See {@code BoxOfficeBaselineServiceImpl}
 * for how each component is derived.
 */
public record BoxOfficeMovieBaseline(
        double adjustedBudgetUsd,
        double rStar,
        double rDirector,
        double rConcept,
        double rIP,
        double baselineB0Usd) {
}
