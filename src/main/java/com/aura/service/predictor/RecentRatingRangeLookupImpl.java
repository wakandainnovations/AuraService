package com.aura.service.predictor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

/**
 * Native query over {@code india_movies_data_collection}. An actor's films are the rows naming them
 * in any of the five cast slots, the same way AuraPredictor builds its cast history. {@code director}
 * and {@code music_director} can hold several comma-separated names, so they're split on commas. The
 * table's key includes language, so one film can have several rows; they're collapsed to one per
 * (title, release date) before taking the last 3.
 */
@Service
public class RecentRatingRangeLookupImpl implements RecentRatingRangeLookup {

    private static final String ACTOR_MATCH =
            "LOWER(TRIM(:name)) IN (LOWER(TRIM(lead_actor_1)), LOWER(TRIM(lead_actor_2))," +
            "    LOWER(TRIM(lead_actor_3)), LOWER(TRIM(lead_actor_4)), LOWER(TRIM(lead_actor_5)))";

    // %s is the comma-separated credit column.
    private static final String LISTED_MATCH =
            "LOWER(TRIM(:name)) IN (SELECT LOWER(TRIM(unnest(string_to_array(%s, ',')))))";

    // %s is the match condition. release_date is free text and not always a full ISO date, so only
    // full "YYYY-MM-DD" rows take part - those order and compare correctly as strings.
    private static final String HIGHEST_RECENT_RATING_RANGE_SQL =
            "SELECT MAX(rating_range) FROM (" +
            "  SELECT LOWER(TRIM(movie_name)) AS title, release_date, MAX(rating_range) AS rating_range" +
            "  FROM india_movies_data_collection" +
            "  WHERE %s" +
            "    AND release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND release_date < :beforeDate" +
            "  GROUP BY LOWER(TRIM(movie_name)), release_date" +
            "  ORDER BY release_date DESC" +
            "  LIMIT 3" +
            ") recent";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Integer highestRecentRatingRange(Role role, String name, String beforeDate) {
        String match = switch (role) {
            case LEAD_ACTOR -> ACTOR_MATCH;
            case DIRECTOR -> LISTED_MATCH.formatted("director");
            case MUSIC_DIRECTOR -> LISTED_MATCH.formatted("music_director");
        };
        Object result = entityManager.createNativeQuery(HIGHEST_RECENT_RATING_RANGE_SQL.formatted(match))
                .setParameter("name", name)
                .setParameter("beforeDate", beforeDate)
                .getSingleResult();
        return result == null ? null : ((Number) result).intValue();
    }
}
