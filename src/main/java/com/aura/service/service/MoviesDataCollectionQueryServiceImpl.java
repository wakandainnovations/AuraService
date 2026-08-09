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

    private static final String RELEASE_DAY_OF_WEEK_SQL =
            "SELECT EXTRACT(DOW FROM release_date) AS dow, COUNT(*) AS cnt, AVG(revenue) AS avg_revenue " +
            "FROM movies_data_collection " +
            "WHERE genre = :genre AND LOWER(language) = LOWER(:language) " +
            "AND release_date IS NOT NULL AND revenue IS NOT NULL " +
            "GROUP BY EXTRACT(DOW FROM release_date)";

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
