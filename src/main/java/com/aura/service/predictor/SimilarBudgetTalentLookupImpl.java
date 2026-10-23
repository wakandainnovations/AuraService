package com.aura.service.predictor;

import com.aura.service.predictor.RecentRatingRangeLookup.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Native query over {@code india_movies_data_collection}. A lead actor is {@code lead_actor_1};
 * {@code director} and {@code music_director} can hold several comma-separated names, so every
 * column is split on commas. The table's key includes language, so one film can have several rows;
 * they're collapsed to one per (title, release date) first, as the rating-range lookup does.
 *
 * <p>A person's home language is the original language (the first one listed) of most of their
 * films in the role, so a Telugu star's one Tamil film doesn't make them a Tamil actor. Music
 * directors instead count for every language they've worked in. A film is
 * released in every language listed for it, e.g. "kannada, hindi, telugu" for a dubbed release.
 */
@Service
public class SimilarBudgetTalentLookupImpl implements SimilarBudgetTalentLookup {

    // First %s is the credit column, second the language condition. release_date is free text and
    // not always a full ISO date, so only full "YYYY-MM-DD" rows count as released - those order
    // and compare correctly as strings.
    private static final String BEST_SIMILAR_BUDGET_FILMS_SQL =
            "WITH credits AS (" +
            "  SELECT LOWER(TRIM(name)) AS name_key, MIN(TRIM(name)) AS name, MIN(movie_name) AS movie_name," +
            "      release_date, MIN(language) AS language," +
            "      LOWER(TRIM(split_part(MIN(language), ',', 1))) AS original_language," +
            "      BOOL_OR(LOWER(TRIM(:language)) IN" +
            "          (SELECT LOWER(TRIM(unnest(string_to_array(language, ',')))))) AS released_in_language," +
            "      MAX(budget) AS budget, MAX(revenue) AS revenue" +
            "  FROM india_movies_data_collection, unnest(string_to_array(%s, ',')) AS name" +
            "  WHERE TRIM(name) <> ''" +
            "  GROUP BY LOWER(TRIM(name)), LOWER(TRIM(movie_name)), release_date" +
            "), people AS (" +
            "  SELECT name_key, mode() WITHIN GROUP (ORDER BY original_language) AS home_language," +
            "      BOOL_OR(original_language = LOWER(TRIM(:language))) AS worked_in_language," +
            "      BOOL_OR(released_in_language) AS reaches_language" +
            "  FROM credits" +
            "  GROUP BY name_key" +
            "), released AS (" +
            "  SELECT c.*, ROW_NUMBER() OVER (PARTITION BY c.name_key ORDER BY c.release_date DESC) AS recency" +
            "  FROM credits c" +
            "  WHERE c.release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND c.release_date < :beforeDate" +
            "), best AS (" +
            "  SELECT DISTINCT ON (r.name_key) r.name, r.movie_name, r.release_date, r.language, r.budget, r.revenue" +
            "  FROM released r JOIN people p ON p.name_key = r.name_key" +
            "  WHERE r.budget BETWEEN :minBudget AND :maxBudget AND r.revenue >= :minRevenueRatio * r.budget" +
            "    AND (r.recency <= 3 OR r.release_date >= :recentSince)" +
            "    AND %s" +
            "  ORDER BY r.name_key, r.revenue / r.budget DESC, r.release_date DESC" +
            ")" +
            " SELECT name, movie_name, release_date, language, budget, revenue FROM best" +
            " ORDER BY revenue / budget DESC, release_date DESC" +
            " LIMIT :limit";

    private static final String HOME_LANGUAGE = "p.home_language = LOWER(TRIM(:language))";
    private static final String WORKED_IN_LANGUAGE = "p.worked_in_language";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<SimilarBudgetFilm> bestSimilarBudgetFilms(Role role, double minBudget, double maxBudget,
                                                          double minRevenueRatio, String language, boolean panIndia,
                                                          String recentSince, String beforeDate, int limit) {
        String column = switch (role) {
            case LEAD_ACTOR -> "lead_actor_1";
            case DIRECTOR -> "director";
            case MUSIC_DIRECTOR -> "music_director";
        };
        String fromLanguage = role == Role.MUSIC_DIRECTOR ? WORKED_IN_LANGUAGE : HOME_LANGUAGE;
        String languageMatch = panIndia ? "(" + fromLanguage + " OR p.reaches_language)" : fromLanguage;
        List<Object[]> rows = entityManager.createNativeQuery(BEST_SIMILAR_BUDGET_FILMS_SQL.formatted(
                        column, languageMatch))
                .setParameter("minBudget", minBudget)
                .setParameter("maxBudget", maxBudget)
                .setParameter("minRevenueRatio", minRevenueRatio)
                .setParameter("language", language)
                .setParameter("recentSince", recentSince)
                .setParameter("beforeDate", beforeDate)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream()
                .map(row -> new SimilarBudgetFilm((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                        ((Number) row[4]).doubleValue(), ((Number) row[5]).doubleValue()))
                .toList();
    }
}
