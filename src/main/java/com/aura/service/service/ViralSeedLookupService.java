package com.aura.service.service;

import java.util.List;

/**
 * Keyword-scoped "viral seed" lookup from AuraMath's {@code /api/marketing/viral-seeds} endpoint — the
 * top accounts to strategically seed promotional content with to trigger spread, ranked by a composite
 * of Hawkes infectivity (α), MOI score, and cross-platform reach. Distinct from
 * {@link MovieBuffLookupService} (existing positive fans to activate) and
 * {@link TopSpreaderLookupService} (general influence ranking): this is specifically about who to seed
 * new content with, regardless of prior sentiment toward the movie.
 *
 * <p>Defined as an interface (mirroring {@link MovieBuffLookupService}) so
 * {@link RecommendedActionCandidateServiceImplTest} can mock this service directly - this project's
 * Java 25 setup breaks Mockito's inline mocking of concrete classes.
 */
public interface ViralSeedLookupService {

    /** {@code profileUrl} is populated only when AuraMath's element carries its {@code platform_handles}
     *  shape (see {@link AuthorProfileLinkResolver}) - null otherwise, never fabricated from author. */
    record ViralSeed(String author, String primaryPlatform, String profileUrl) {
    }

    /** Empty list if AuraMath is unavailable, the response can't be parsed, or there are none. */
    List<ViralSeed> getViralSeeds(String keyword);
}
