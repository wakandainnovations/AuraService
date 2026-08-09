package com.aura.service.service;

import java.util.List;

/**
 * Thin native-query seam over the {@code movies_data_collection} table, extracted from
 * {@link RecommendedActionCandidateServiceImpl} purely so that service's candidate-generation logic
 * can be unit-tested by mocking this interface. {@code jakarta.persistence.EntityManager} itself
 * cannot be mocked under this project's Java 25 toolchain - it extends {@code java.lang.AutoCloseable},
 * which Mockito's inline mock maker cannot instrument (a JDK-module restriction, not a concrete-vs-
 * interface issue).
 */
public interface MoviesDataCollectionQueryService {

    /**
     * Rows of {@code [count, avgRevenue]} (a single aggregate row) for comparable-movie budget comps:
     * same genre + language, budget within {@code [minBudget, maxBudget]}, revenue not null.
     */
    List<Object[]> findGenreLanguageBudgetComps(String genre, String language, double minBudget, double maxBudget);

    /**
     * Rows of {@code [dayOfWeek, count, avgRevenue]} (Postgres {@code EXTRACT(DOW ...)}, 0 = Sunday)
     * for same genre + language releases, grouped by release day-of-week.
     */
    List<Object[]> findReleaseDayOfWeekStats(String genre, String language);
}
