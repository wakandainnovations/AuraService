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
     * overlapping genre (see {@link MoviesDataCollectionQueryServiceImpl#genreOverlaps} - at least one
     * shared genre token, not an exact match of the full comma-separated string) + same language,
     * budget within {@code [minBudget, maxBudget]}, revenue not null.
     */
    List<Object[]> findGenreLanguageBudgetComps(String genre, String language, double minBudget, double maxBudget);

    /**
     * Rows of {@code [dayOfWeek, count, avgRevenue, exampleTitles]} (Postgres {@code EXTRACT(DOW
     * ...)}, 0 = Sunday; {@code exampleTitles} a {@code List<String>} of a few real comparable-movie
     * names in that bucket) for overlapping-genre (see
     * {@link MoviesDataCollectionQueryServiceImpl#genreOverlaps}) + same language releases, grouped by
     * release day-of-week.
     */
    List<Object[]> findReleaseDayOfWeekStats(String genre, String language);
}
