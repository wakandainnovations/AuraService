package com.aura.service.service;

import java.util.List;

/**
 * Thin native-query seam over the {@code movie_marketing_tactics} table, following the same
 * {@code @PersistenceContext EntityManager}-behind-a-mockable-interface pattern as
 * {@link MoviesDataCollectionQueryService} (see that interface's javadoc for why - Mockito can't
 * instrument {@code EntityManager} under this project's Java 25 toolchain).
 */
public interface MovieMarketingTacticsQueryService {

    /**
     * Rows of {@code [movieName, releaseYear, mainClassificationName, subClassificationName,
     * tacticText]} for {@code movie_marketing_tactics} entries whose movie joins to a
     * {@code movies_data_collection} row (same movie name + language, release year matching the
     * comp's release date) with genre overlapping {@code genre} - see
     * {@link MoviesDataCollectionQueryServiceImpl#genreOverlaps} for the cross-taxonomy token-overlap
     * comparison this reuses (movie_marketing_tactics carries no genre of its own).
     */
    List<Object[]> findPeerTactics(String genre, String language);
}
