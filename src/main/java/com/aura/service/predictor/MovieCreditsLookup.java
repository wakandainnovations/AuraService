package com.aura.service.predictor;

import java.util.List;

/**
 * Every distinct name credited in {@code india_movies_data_collection}, per role. Names are trimmed,
 * de-duplicated case-insensitively and sorted alphabetically.
 */
public interface MovieCreditsLookup {

    /**
     * A credited person and the highest {@code rating_range} among their last 3 films in that role
     * released by today, or null when none of them has one.
     */
    record RatedName(String name, Integer highestRecentRatingRange) {}

    /** Names from any of the five {@code lead_actor_*} slots. */
    List<RatedName> actors();

    List<RatedName> directors();

    List<RatedName> musicDirectors();

    List<String> productionCompanies();
}
