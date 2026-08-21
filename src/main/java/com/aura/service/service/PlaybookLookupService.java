package com.aura.service.service;

import java.util.List;

/**
 * Defined as an interface (mirroring {@link GenreMarketingLookupService}) so
 * {@code RecommendedActionCandidateServiceImplTest} can mock this service directly rather than
 * constructing the real AuraMath-backed implementation - this project's Java 25 setup breaks
 * Mockito's inline mocking of concrete classes.
 */
public interface PlaybookLookupService {

    /** One row of AuraMath's F7 {@code playbook_patterns} for a resolved (industry, language) cohort. */
    record PlaybookPattern(List<String> patternSequence, long supportTopTier, long supportBottomTier,
                            double pValue, double fdrQValue, long nEntities) {
    }

    /** Empty list if AuraMath is unavailable, reports insufficient_history, or the response can't be parsed. */
    List<PlaybookPattern> getPlaybookPatterns(String industry, String language);
}
