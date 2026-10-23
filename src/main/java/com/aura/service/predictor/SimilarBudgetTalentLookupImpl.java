package com.aura.service.predictor;

import com.aura.service.predictor.RecentRatingRangeLookup.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Native query over {@code india_movies_data_collection}. A lead actor is {@code lead_actor_1};
 * {@code director} and {@code music_director} can hold several comma-separated names, so every
 * column is split on commas. The table's key includes language, so one film can have several rows;
 * they're collapsed to one per (title, release date) first, as the rating-range lookup does.
 *
 * <p>A person's home language is the original language (the first one listed) of most of their
 * films in the role, so a Telugu star's one Tamil film doesn't make them a Tamil actor.
 */
@Service
public class SimilarBudgetTalentLookupImpl implements SimilarBudgetTalentLookup {

    // First %s is the credit column, second the optional home-language condition. release_date is
    // free text and not always a full ISO date, so only full "YYYY-MM-DD" rows count in the band -
    // those compare correctly as strings.
    private static final String BEST_SIMILAR_BUDGET_FILMS_SQL =
            "WITH credits AS (" +
            "  SELECT LOWER(TRIM(name)) AS name_key, MIN(TRIM(name)) AS name, MIN(movie_name) AS movie_name," +
            "      release_date, MIN(language) AS language," +
            "      LOWER(TRIM(split_part(MIN(language), ',', 1))) AS original_language," +
            "      MAX(budget) AS budget, MAX(revenue) AS revenue" +
            "  FROM india_movies_data_collection, unnest(string_to_array(%s, ',')) AS name" +
            "  WHERE TRIM(name) <> ''" +
            "  GROUP BY LOWER(TRIM(name)), LOWER(TRIM(movie_name)), release_date" +
            "), home AS (" +
            "  SELECT name_key, mode() WITHIN GROUP (ORDER BY original_language) AS home_language" +
            "  FROM credits" +
            "  GROUP BY name_key" +
            "), best AS (" +
            "  SELECT DISTINCT ON (c.name_key) c.name, c.movie_name, c.release_date, c.language, c.budget, c.revenue" +
            "  FROM credits c JOIN home h ON h.name_key = c.name_key" +
            "  WHERE c.budget BETWEEN :minBudget AND :maxBudget AND c.revenue > 0" +
            "    AND c.release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'" +
            "    AND c.release_date >= :fromDate AND c.release_date < :beforeDate" +
            "    %s" +
            "  ORDER BY c.name_key, c.revenue / c.budget DESC, c.release_date DESC" +
            ")" +
            " SELECT name, movie_name, release_date, language, budget, revenue FROM best" +
            " ORDER BY revenue / budget DESC, release_date DESC" +
            " LIMIT :limit";

    private static final String HOME_LANGUAGE_MATCH = "AND h.home_language = LOWER(TRIM(:homeLanguage))";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<SimilarBudgetFilm> bestSimilarBudgetFilms(Role role, double minBudget, double maxBudget,
                                                          String homeLanguage, String fromDate, String beforeDate,
                                                          int limit) {
        String column = switch (role) {
            case LEAD_ACTOR -> "lead_actor_1";
            case DIRECTOR -> "director";
            case MUSIC_DIRECTOR -> "music_director";
        };
        Query query = entityManager.createNativeQuery(BEST_SIMILAR_BUDGET_FILMS_SQL.formatted(
                        column, homeLanguage == null ? "" : HOME_LANGUAGE_MATCH))
                .setParameter("minBudget", minBudget)
                .setParameter("maxBudget", maxBudget)
                .setParameter("fromDate", fromDate)
                .setParameter("beforeDate", beforeDate)
                .setParameter("limit", limit);
        if (homeLanguage != null) {
            query.setParameter("homeLanguage", homeLanguage);
        }
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> new SimilarBudgetFilm((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                        ((Number) row[4]).doubleValue(), ((Number) row[5]).doubleValue()))
                .toList();
    }
}
