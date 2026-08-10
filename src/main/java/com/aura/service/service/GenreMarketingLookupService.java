package com.aura.service.service;

/**
 * Genre-scoped (never budget-scoped) audience-reach data from AuraMath's {@code /api/marketing/genre/*}
 * endpoints — see {@link com.aura.service.proxy.AuraMathMarketingProxyController}. Unlike
 * {@link MoviesDataCollectionQueryService}'s box-office comps, this data doesn't require a budget
 * figure at all, so it's the one real-data source {@link RecommendedActionCandidateServiceImpl} can
 * ground a candidate in for an entity with no budget on file - exactly the small/independent-movie
 * case the comps-based candidates can't reach.
 *
 * <p>Defined as an interface (mirroring {@link MoviesDataCollectionQueryService}) so
 * {@link RecommendedActionCandidateServiceImplTest} can mock this service directly rather than
 * constructing the real AuraMath-backed implementation - this project's Java 25 setup breaks
 * Mockito's inline mocking of concrete classes.
 */
public interface GenreMarketingLookupService {

    record GenreReach(Long totalViewers, String topChannel) {
    }

    /** Null if AuraMath is unavailable or returned nothing usable from either endpoint. */
    GenreReach getGenreReach(String genre);
}
