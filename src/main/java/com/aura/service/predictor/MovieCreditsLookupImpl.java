package com.aura.service.predictor;

import com.aura.service.predictor.MovieCreditsLookup.RatedName;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Native queries over {@code india_movies_data_collection}. {@code director} and
 * {@code production_companies} can hold several comma-separated names, so every column is split on
 * commas. {@code director} is used over {@code directors}: it's filled on every row and is what
 * AuraPredictor builds director history from.
 *
 * <p>For a person's last 3 films, the table's key includes language, so one film can have several
 * rows; they're collapsed to one per (title, release date) first, as the budget-risk rating-range
 * lookup does.
 */
@Service
public class MovieCreditsLookupImpl implements MovieCreditsLookup {

    // %s is a list of text expressions, each possibly comma-separated. The first-sorted spelling
    // stands for names that differ only in case.
    private static final String DISTINCT_NAMES_SQL =
            "SELECT MIN(name) FROM (" +
            "  SELECT TRIM(unnest(string_to_array(credit, ','))) AS name" +
            "  FROM india_movies_data_collection, unnest(ARRAY[%s]) AS credit" +
            ") names" +
            " WHERE name <> ''" +
            " GROUP BY LOWER(name)" +
            " ORDER BY LOWER(MIN(name))";

    // %s as above. release_date is free text and not always a full ISO date, so only full
    // "YYYY-MM-DD" rows released by :today count as dated - those order and compare correctly as
    // strings. Dated films rank first, so a name's 3 most recent dated films are recency 1-3.
    private static final String RATED_NAMES_SQL =
            "WITH credits AS (" +
            "  SELECT TRIM(unnest(string_to_array(credit, ','))) AS name, LOWER(TRIM(movie_name)) AS title," +
            "      release_date, rating_range" +
            "  FROM india_movies_data_collection, unnest(ARRAY[%s]) AS credit" +
            "), films AS (" +
            "  SELECT LOWER(name) AS name_key, MIN(name) AS name, MAX(rating_range) AS rating_range," +
            "      COALESCE(release_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND release_date <= :today, false) AS dated," +
            "      release_date" +
            "  FROM credits" +
            "  WHERE name <> ''" +
            "  GROUP BY LOWER(name), title, release_date" +
            "), ranked AS (" +
            "  SELECT name_key, name, rating_range, dated," +
            "      ROW_NUMBER() OVER (PARTITION BY name_key ORDER BY dated DESC, release_date DESC) AS recency" +
            "  FROM films" +
            ")" +
            " SELECT MIN(name), MAX(rating_range) FILTER (WHERE dated AND recency <= 3)" +
            " FROM ranked" +
            " GROUP BY name_key" +
            " ORDER BY LOWER(MIN(name))";

    private static final String ACTOR_COLUMNS = "lead_actor_1, lead_actor_2, lead_actor_3, lead_actor_4, lead_actor_5";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<RatedName> actors() {
        return ratedNames(ACTOR_COLUMNS);
    }

    @Override
    public List<RatedName> directors() {
        return ratedNames("director");
    }

    @Override
    public List<RatedName> musicDirectors() {
        return ratedNames("music_director");
    }

    @Override
    public List<String> productionCompanies() {
        return distinctNames("production_companies");
    }

    @SuppressWarnings("unchecked")
    private List<String> distinctNames(String columns) {
        return entityManager.createNativeQuery(DISTINCT_NAMES_SQL.formatted(columns)).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<RatedName> ratedNames(String columns) {
        List<Object[]> rows = entityManager.createNativeQuery(RATED_NAMES_SQL.formatted(columns))
                .setParameter("today", LocalDate.now().toString())
                .getResultList();
        return rows.stream()
                .map(row -> new RatedName((String) row[0], row[1] == null ? null : ((Number) row[1]).intValue()))
                .toList();
    }
}
