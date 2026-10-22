package com.aura.service.predictor;

import java.util.List;

/**
 * Every distinct name credited in {@code india_movies_data_collection}, per role. Names are trimmed,
 * de-duplicated case-insensitively and sorted alphabetically.
 */
public interface MovieCreditsLookup {

    /** Names from any of the five {@code lead_actor_*} slots. */
    List<String> actors();

    List<String> directors();

    List<String> musicDirectors();

    List<String> productionCompanies();
}
