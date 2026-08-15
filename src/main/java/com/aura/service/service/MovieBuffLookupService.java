package com.aura.service.service;

import java.util.List;

/**
 * Keyword-scoped "movie buff" lookup from AuraMath's {@code /api/marketing/movie-buffs/*}
 * endpoint — a distinct classification from {@link TopSpreaderLookupService}'s Hawkes-α top-spreader
 * ranking: AuraMath pre-tags authors as {@code audience_classification = 'Movie Buff'} (positive
 * tone, high branching ratio) in its own {@code author_categories} table, independent of any one
 * keyword, then intersects that with who has actually posted about this movie's tracked keyword.
 *
 * <p>Defined as an interface (mirroring {@link MoviesDataCollectionQueryService}/
 * {@link GenreMarketingLookupService}) so {@link RecommendedActionCandidateServiceImplTest} can mock
 * this service directly - this project's Java 25 setup breaks Mockito's inline mocking of concrete
 * classes.
 */
public interface MovieBuffLookupService {

    /** {@code profileUrl} is populated only when AuraMath's element carries its {@code platform_handles}
     *  shape (see {@link AuthorProfileLinkResolver}) - null otherwise, never fabricated from author. */
    record MovieBuff(String author, String influenceTier, String profileUrl) {
    }

    /** Empty list if AuraMath is unavailable, the response can't be parsed, or there are none. */
    List<MovieBuff> getMovieBuffs(String keyword);
}
