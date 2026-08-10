package com.aura.service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Raw {@code movies_data_collection} native queries, following the same {@code @PersistenceContext
 * EntityManager} native-query style as {@link BoxOfficeBaselineServiceImpl}'s
 * {@code CONCEPT_DENSITY_SQL} / {@code FRANCHISE_MATCH_SQL}.
 */
@Service
public class MoviesDataCollectionQueryServiceImpl implements MoviesDataCollectionQueryService {

    private static final String GENRE_LANGUAGE_BUDGET_COMPS_SQL =
            "SELECT COUNT(*), AVG(revenue) FROM movies_data_collection " +
            "WHERE genre = :genre AND LOWER(language) = LOWER(:language) " +
            "AND budget BETWEEN :minBudget AND :maxBudget AND revenue IS NOT NULL";

    // release_date is a free-text column and not always a full ISO date (sometimes just a bare
    // year, e.g. "2025" — see MovieBacktestRow) — EXTRACT(DOW ...) needs a real date, so this
    // restricts to rows that are a full "YYYY-MM-DD" string before casting, same guard style as
    // BoxOfficeBacktestServiceImpl's ELIGIBLE_MOVIES_SQL.
    // CAST(... AS date), not the "::date" shorthand — Hibernate's native-query parameter parser
    // treats a bare "::" as a bind-parameter prefix and mangles it into invalid SQL.
    private static final String RELEASE_DAY_OF_WEEK_SQL =
            "SELECT EXTRACT(DOW FROM CAST(release_date AS date)) AS dow, COUNT(*) AS cnt, AVG(revenue) AS avg_revenue " +
            "FROM movies_data_collection " +
            "WHERE genre = :genre AND LOWER(language) = LOWER(:language) " +
            "AND release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND revenue IS NOT NULL " +
            "GROUP BY EXTRACT(DOW FROM CAST(release_date AS date))";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findGenreLanguageBudgetComps(String genre, String language, double minBudget, double maxBudget) {
        return entityManager.createNativeQuery(GENRE_LANGUAGE_BUDGET_COMPS_SQL)
                .setParameter("genre", genre)
                .setParameter("language", language)
                .setParameter("minBudget", minBudget)
                .setParameter("maxBudget", maxBudget)
                .getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findReleaseDayOfWeekStats(String genre, String language) {
        return entityManager.createNativeQuery(RELEASE_DAY_OF_WEEK_SQL)
                .setParameter("genre", genre)
                .setParameter("language", language)
                .getResultList();
    }
}
