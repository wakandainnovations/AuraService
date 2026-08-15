package com.aura.service.service;

import java.util.List;

/**
 * Keyword-scoped "movie buff" lookup from AuraMath's {@code /api/marketing/brand-evangelists/*}
 * endpoint — a distinct classification from {@link TopSpreaderLookupService}'s Hawkes-α top-spreader
 * ranking: AuraMath pre-tags authors as {@code audience_classification = 'Brand Evangelist'} (positive
 * tone, high branching ratio) in its own {@code author_categories} table, independent of any one
 * keyword, then intersects that with who has actually posted about this movie's tracked keyword.
 *
 * <p>Defined as an interface (mirroring {@link MoviesDataCollectionQueryService}/
 * {@link GenreMarketingLookupService}) so {@link RecommendedActionCandidateServiceImplTest} can mock
 * this service directly - this project's Java 25 setup breaks Mockito's inline mocking of concrete
 * classes.
 */
public interface MovieBuffLookupService {

    record MovieBuff(String author, String influenceTier) {
    }

    /** Empty list if AuraMath is unavailable, the response can't be parsed, or there are none. */
    List<MovieBuff> getMovieBuffs(String keyword);
}
