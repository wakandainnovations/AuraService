package com.aura.service.predictor;

/** The rating ranges of an actor's recent films in {@code india_movies_data_collection}. */
public interface LeadActorRatingRangeLookup {

    /**
     * The highest {@code rating_range} among the actor's last 3 films released before
     * {@code beforeDate} (YYYY-MM-DD), or null when none of them has one.
     */
    Integer highestRecentRatingRange(String actor, String beforeDate);
}
