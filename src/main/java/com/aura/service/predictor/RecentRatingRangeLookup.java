package com.aura.service.predictor;

/** The rating ranges of a person's recent films in {@code india_movies_data_collection}. */
public interface RecentRatingRangeLookup {

    enum Role { LEAD_ACTOR, DIRECTOR, MUSIC_DIRECTOR }

    /**
     * The highest {@code rating_range} among the last 3 films credited to {@code name} in
     * {@code role} and released before {@code beforeDate} (YYYY-MM-DD), or null when none of them
     * has one.
     */
    Integer highestRecentRatingRange(Role role, String name, String beforeDate);
}
