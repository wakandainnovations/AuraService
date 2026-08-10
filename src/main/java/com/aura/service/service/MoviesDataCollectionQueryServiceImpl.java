package com.aura.service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Raw {@code movies_data_collection} native queries, following the same {@code @PersistenceContext
 * EntityManager} native-query style as {@link BoxOfficeBaselineServiceImpl}'s
 * {@code CONCEPT_DENSITY_SQL} / {@code FRANCHISE_MATCH_SQL}.
 *
 * <p>Genre matching is done in Java, not SQL: {@code movies_data_collection.genre} and this
 * platform's own {@code ManagedEntity.genre} are both comma-separated multi-genre strings, but in
 * different formats and taxonomies (e.g. this platform's {@code "Action,Adventure,Psychedelic"} vs.
 * the comps table's {@code "Action, Romance, Comedy, Thriller"}) - a SQL {@code genre = :genre} exact
 * match against the whole string essentially never matches. {@link #genreOverlaps} instead tokenizes
 * both sides and requires only one shared genre token, case-insensitively. This is pushed to the
 * Java side (fetch by language/budget/date only, filter+aggregate genre overlap in-memory) rather
 * than built as a dynamic SQL OR-of-ILIKE clause, since the languages this platform's movies actually
 * use (Kannada/Tamil/Telugu/Hindi/Malayalam) each have at most ~11k comps rows - cheap to pull and
 * filter in-process, and far simpler than binding a variable-length ILIKE/array clause.
 */
@Service
public class MoviesDataCollectionQueryServiceImpl implements MoviesDataCollectionQueryService {

    private static final String GENRE_LANGUAGE_BUDGET_COMPS_SQL =
            "SELECT genre, revenue FROM movies_data_collection " +
            "WHERE LOWER(language) = LOWER(:language) " +
            "AND budget BETWEEN :minBudget AND :maxBudget AND revenue IS NOT NULL";

    // release_date is a free-text column and not always a full ISO date (sometimes just a bare
    // year, e.g. "2025" — see MovieBacktestRow) — EXTRACT(DOW ...) needs a real date, so this
    // restricts to rows that are a full "YYYY-MM-DD" string before casting, same guard style as
    // BoxOfficeBacktestServiceImpl's ELIGIBLE_MOVIES_SQL.
    // CAST(... AS date), not the "::date" shorthand — Hibernate's native-query parameter parser
    // treats a bare "::" as a bind-parameter prefix and mangles it into invalid SQL.
    private static final String RELEASE_DAY_OF_WEEK_SQL =
            "SELECT EXTRACT(DOW FROM CAST(release_date AS date)) AS dow, genre, revenue " +
            "FROM movies_data_collection " +
            "WHERE LOWER(language) = LOWER(:language) " +
            "AND release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND revenue IS NOT NULL";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findGenreLanguageBudgetComps(String genre, String language, double minBudget, double maxBudget) {
        Set<String> genreTokens = tokenizeGenre(genre);
        if (genreTokens.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery(GENRE_LANGUAGE_BUDGET_COMPS_SQL)
                .setParameter("language", language)
                .setParameter("minBudget", minBudget)
                .setParameter("maxBudget", maxBudget)
                .getResultList();

        long count = 0;
        double revenueSum = 0;
        for (Object[] row : rows) {
            if (!genreOverlaps(genreTokens, (String) row[0])) {
                continue;
            }
            count++;
            revenueSum += ((Number) row[1]).doubleValue();
        }
        if (count == 0) {
            return List.of();
        }
        return List.<Object[]>of(new Object[]{count, revenueSum / count});
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> findReleaseDayOfWeekStats(String genre, String language) {
        Set<String> genreTokens = tokenizeGenre(genre);
        if (genreTokens.isEmpty()) {
            return List.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery(RELEASE_DAY_OF_WEEK_SQL)
                .setParameter("language", language)
                .getResultList();

        Map<Integer, Long> countByDow = new LinkedHashMap<>();
        Map<Integer, Double> revenueSumByDow = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (!genreOverlaps(genreTokens, (String) row[1])) {
                continue;
            }
            int dow = ((Number) row[0]).intValue();
            countByDow.merge(dow, 1L, Long::sum);
            revenueSumByDow.merge(dow, ((Number) row[2]).doubleValue(), Double::sum);
        }

        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : countByDow.entrySet()) {
            int dow = entry.getKey();
            long count = entry.getValue();
            result.add(new Object[]{dow, count, revenueSumByDow.get(dow) / count});
        }
        return result;
    }

    /** Splits a comma-separated genre string into lowercased, trimmed, non-blank tokens. */
    static Set<String> tokenizeGenre(String genre) {
        if (genre == null || genre.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : genre.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    /** True if any of {@code entityGenreTokens} appears as a genre token in {@code compsGenre}. */
    static boolean genreOverlaps(Set<String> entityGenreTokens, String compsGenre) {
        if (entityGenreTokens.isEmpty() || compsGenre == null || compsGenre.isBlank()) {
            return false;
        }
        for (String token : compsGenre.split(",")) {
            if (entityGenreTokens.contains(token.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
