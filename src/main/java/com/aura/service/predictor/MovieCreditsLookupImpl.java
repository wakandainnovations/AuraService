package com.aura.service.predictor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Native queries over {@code india_movies_data_collection}. {@code director} and
 * {@code production_companies} can hold several comma-separated names, so every column is split on
 * commas. {@code director} is used over {@code directors}: it's filled on every row and is what
 * AuraPredictor builds director history from.
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

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<String> actors() {
        return distinctNames("lead_actor_1, lead_actor_2, lead_actor_3, lead_actor_4, lead_actor_5");
    }

    @Override
    public List<String> directors() {
        return distinctNames("director");
    }

    @Override
    public List<String> musicDirectors() {
        return distinctNames("music_director");
    }

    @Override
    public List<String> productionCompanies() {
        return distinctNames("production_companies");
    }

    @SuppressWarnings("unchecked")
    private List<String> distinctNames(String columns) {
        return entityManager.createNativeQuery(DISTINCT_NAMES_SQL.formatted(columns)).getResultList();
    }
}
